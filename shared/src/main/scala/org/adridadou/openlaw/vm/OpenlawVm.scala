package org.adridadou.openlaw.vm

import java.time.{Clock, LocalDateTime, ZoneOffset}

import cats.Eq
import cats.implicits._
import org.adridadou.openlaw.parser.template._
import org.adridadou.openlaw.parser.template.expressions.Expression
import org.adridadou.openlaw.parser.template.variableTypes._
import org.adridadou.openlaw.values.{ContractDefinition, ContractId, TemplateId, TemplateParameters}
import org.adridadou.openlaw.oracles._
import slogging.LazyLogging

import scala.reflect.ClassTag

case class Signature(userId:UserId, signature:OpenlawSignatureEvent)

case class Executions(executionMap:Map[LocalDateTime,OpenlawExecution] = Map()) {
  def update(key:LocalDateTime, value:OpenlawExecution):Executions = {
    this.copy(executionMap = executionMap + (key -> value))
  }
}

case class OpenlawVmState( contents:Map[TemplateId, String] = Map(),
                           templates:Map[TemplateId, CompiledTemplate] = Map(),
                           optExecutionResult:Option[TemplateExecutionResult],
                           definition:ContractDefinition,
                           state:TemplateParameters,
                           executions:Map[VariableName, Executions],
                           signatures:Map[UserId, SignatureEvent],
                           signatureProofs:Map[UserId, SignatureProof] = Map(),
                           events:List[OpenlawVmEvent] = List(),
                           executionEngine: OpenlawExecutionEngine,
                           executionState:ContractExecutionState,
                           clock:Clock) extends LazyLogging {

  def updateTemplate(id: TemplateId, compiledTemplate: CompiledTemplate, event:LoadTemplate): OpenlawVmState =
    update(templates + (id -> compiledTemplate), state, executionResult, event)
      .copy(contents = contents + (id -> event.content))

  def updateExecutionState(newState: ContractExecutionState, event: OpenlawVmEvent): OpenlawVmState = {
    this.copy(executionState = newState, events = event :: events)
  }

  def updateParameter(name:VariableName, value:String, event:OpenlawVmEvent) :OpenlawVmState = {
    val newParams = state + (name -> value)
    update(templates, newParams, createNewExecutionResult(newParams, templates, signatureProofs), event).copy(
      state = newParams
    )
  }

  def executionResult:Option[TemplateExecutionResult] = optExecutionResult match {
    case Some(result) => Some(result)
    case None => createNewExecutionResult(state, templates, signatureProofs)
  }

  private def update(templates:Map[TemplateId, CompiledTemplate], parameters:TemplateParameters, optExecution:Option[TemplateExecutionResult], event:OpenlawVmEvent) : OpenlawVmState = {
    val execution = optExecution match {
      case Some(executionResult) =>
        Some(executionResult)
      case None =>
        createNewExecutionResult(parameters, templates, signatureProofs)
    }

    execution.map(executeContract(templates, _) match {
      case Right(newResult) =>
        this.copy(optExecutionResult = Some(newResult))

      case Left(ex) =>
        logger.warn(ex)
        this
    }).getOrElse(this).copy(
      templates = templates,
      events = event :: events
    )
  }

  private def executeContract(currentTemplates:Map[TemplateId, CompiledTemplate], execution: TemplateExecutionResult):Either[String,TemplateExecutionResult] = execution.state match {
    case ExecutionFinished =>
      Right(execution)

    case _ =>
      val templates = definition.templates.flatMap({case (templateDefinition, id) => currentTemplates.get(id).map(templateDefinition -> _)})
      executionEngine.resumeExecution(execution, templates)
  }

  def createNewExecutionResult(signatureProofs:Map[UserId, SignatureProof]):Option[TemplateExecutionResult] =
    createNewExecutionResult(state, templates, signatureProofs)

  def createNewExecutionResult(params:TemplateParameters, templates:Map[TemplateId, CompiledTemplate],signatureProofs:Map[UserId, SignatureProof]):Option[TemplateExecutionResult] = {
    val templateDefinitions = definition.templates.flatMap({case (templateDefinition, id) => templates.get(id).map(templateDefinition -> _)})
    templates.get(definition.mainTemplate).map(executionEngine.execute(_, params, templateDefinitions, signatureProofs)) match {
      case None => None
      case Some(Right(result)) =>
        Some(result)
      case Some(Left(ex)) =>
        logger.warn(ex)
        None
    }
  }
}

case class OpenlawVm(contractDefinition: ContractDefinition, cryptoService: CryptoService, parser:OpenlawTemplateLanguageParserService, identityOracles:Map[String, OpenlawIdentityOracle], oracles:Seq[OpenlawOracle[_]]) extends LazyLogging {
  private val templateOracle = TemplateLoadOracle(cryptoService)
  val contractId:ContractId = contractDefinition.id(cryptoService)
  private val expressionParser = new ExpressionParserService

  private var state:OpenlawVmState = OpenlawVmState(
    state = contractDefinition.parameters,
    definition = contractDefinition,
    executionEngine = new OpenlawExecutionEngine(),
    executions = Map(),
    signatures = Map(),
    optExecutionResult = None,
    executionState = ContractCreated,
    clock = parser.internalClock
  )

  def isSignatureValid(data:EthereumData, identity: Identity, event:SignatureEvent):Boolean = {
    identity.identifiers
      .exists(identifier => identityOracles.get(identifier.identityProviderId).exists(_.isSignatureValid(data, identifier, event)))
  }
  def allIdentities:Seq[Identity] = {
    state.executionResult.map(executionResult => {
      executionResult.getAllExecutedVariables
        .flatMap({case (result, name) => result.getVariable(name).map(variable => (result, variable))}).flatMap({ case (result, variable) =>
        variable.varType(result) match {
          case IdentityType =>
            variable.evaluate(result).map(VariableType.convert[Identity]).toSeq
          case collectionType:CollectionType if collectionType.typeParameter === IdentityType =>
            variable.evaluate(result)
              .map(VariableType.convert[CollectionValue])
              .map(_.list).getOrElse(Seq())
              .map(VariableType.convert[Identity])
          case structureType:DefinedStructureType if structureType.structure.typeDefinition.values.exists(_ === IdentityType) =>
            val values = variable.evaluate(result).map(VariableType.convert[Map[VariableName, Any]]).getOrElse(Map())

            structureType.structure.typeDefinition
              .flatMap({
                case (name, varType) if varType === IdentityType =>
                  values.get(name).map(VariableType.convert[Identity])
                case _ => None
              })

          case _ =>
            Seq()
        }
      })
    }).getOrElse(Seq())
  }

  def allNextActions: Seq[ActionInfo] = allActions
    .flatMap(info => info.action.nextActionSchedule(info.executionResult, executions(info.name)).map(nextDate => (info, nextDate)))
    .sortBy({case (_, nextDate) => nextDate.toEpochSecond(ZoneOffset.UTC)})
    .map({case (info,_) => info})

  def executionState:ContractExecutionState = state.executionState

  def nextActionSchedule:Option[LocalDateTime] = nextAction
    .flatMap(info => info.action.nextActionSchedule(info.executionResult, executions(info.name)))

  def newSignature(identity:Identity, fullName:String, signature:SignatureEvent):OpenlawVm = {
    val userId = identity.userId
    val signatureProofs = state.signatureProofs + (userId -> signature.proof)
    val newExecutionResult = state.createNewExecutionResult(signatureProofs)
    state = state.copy(
      signatures = state.signatures + (userId -> signature),
      signatureProofs = signatureProofs,
      optExecutionResult = newExecutionResult
    )
    this
  }

  def newExecution(name:VariableName, execution: OpenlawExecution):OpenlawVm = {
    val executions = state.executions.getOrElse(name, Executions())
    state = state.copy(executions = state.executions + (name  -> executions.update(execution.scheduledDate, execution)))
    this
  }

  def allExecutions: Map[VariableName, Seq[OpenlawExecution]] = state.executions.map({case (name, executions) => name -> executions.executionMap.values.toSeq.sortBy(_.scheduledDate.toEpochSecond(ZoneOffset.UTC))})

  def executions[T <: OpenlawExecution](name: VariableName)(implicit classTag: ClassTag[T]): Seq[T] = allExecutions.getOrElse(name, Seq()).map(_.asInstanceOf[T])

  def allSignatures:Map[UserId, SignatureEvent] = state.signatures
  def signature(userId:UserId):Option[SignatureEvent] = allSignatures.get(userId)

  def events: Seq[OpenlawVmEvent] = state.events

  def nextAction: Option[ActionInfo] = allNextActions
    .headOption

  def agreements:Seq[StructuredAgreement] =
    executionResult.map(_.agreements).getOrElse(Seq())

  def executionResultState:TemplateExecutionState =
    executionResult.map(_.state).getOrElse(ExecutionReady)

  def allActions:Seq[ActionInfo] = executionState match {
    case ContractRunning =>
      executionResult.map(_.allActions()).getOrElse(Seq())
    case ContractResumed =>
      executionResult.map(_.allActions()).getOrElse(Seq())
    case ContractCreated =>
      executionResult
        .map(_.allIdentityIdentifiers).getOrElse(Map())
        .flatMap({case (userId, identifiers) => generateSignatureAction(userId, identifiers)}).toSeq
    case _ =>
      Seq()
  }

  private def generateSignatureAction(userId:UserId, identifiers:Seq[IdentityIdentifier]):Option[ActionInfo] =
    this.executionResult.map(ActionInfo(SignatureAction(userId, identifiers), VariableName(""), _))

  def template(definition: TemplateSourceIdentifier):CompiledTemplate = state.templates(contractDefinition.templates(definition))
  def template(id:TemplateId):CompiledTemplate = state.templates(id)
  def parameters:TemplateParameters = state.state

  def mainTemplate:CompiledTemplate = state.templates(contractDefinition.mainTemplate)

  def executionResult:Option[TemplateExecutionResult] = state.executionResult

  def content(templateId: TemplateId): String = state.contents(templateId)

  def getAllVariables(varType: VariableType):Seq[(TemplateExecutionResult, VariableDefinition)] =
    state.executionResult.map(_.getVariables(varType)).getOrElse(Seq())

  def getAllVariableValues[T](varType: VariableType)(implicit classTag:ClassTag[T]):Seq[T] =
    getAllVariables(varType).flatMap({case (executionResult, variable) =>
      variable.evaluate(executionResult).map(VariableType.convert[T])
    })

  def parseExpression(expr:String):Either[String, Expression] = expressionParser.parseExpression(expr)

  def evaluate[T](variable:VariableName)(implicit classTag:ClassTag[T]):Either[String, T] =
    evaluate(variable.name)

  def evaluate[T](expr:String)(implicit classTag:ClassTag[T]):Either[String, T] = {
    executionResult match {
      case Some(result) =>
        parseExpression(expr).flatMap(evaluate(result, _))
      case None =>
        Left("the VM has not been executed yet!")
    }
  }

  def evaluate[T](expr:Expression)(implicit classTag:ClassTag[T]):Either[String, T] = {
    executionResult match {
      case Some(result) =>
        evaluate(result, expr)
      case None =>
        Left("the VM has not been executed yet!")
    }
  }

  def evaluate[T](executionResult: TemplateExecutionResult, expr:String)(implicit classTag:ClassTag[T]):Either[String, T] = parseExpression(expr)
    .flatMap(evaluate[T](executionResult,_))

  def evaluate[T](executionResult: TemplateExecutionResult, expr:Expression)(implicit classTag:ClassTag[T]):Either[String, T] = expr.evaluate(executionResult) match {
    case Some(value:T) => Right(value)
    case Some(value) => Left(s"conversion error. Was expecting ${classTag.runtimeClass.getName} but got ${value.getClass.getName}")
    case None => Left(s"could not resolve ${expr.toString}")
  }

  def applyEvent(event:OpenlawVmEvent):Either[String, OpenlawVm] = state.executionState match {
    case ContractCreated =>
      event match {
        case signature:SignatureEvent =>
          processSignature(signature)
        case e:LoadTemplate =>
          templateOracle.incoming(this, e)
        case _ =>
          Left("the virtual machine is in creation state. The only events allowed are signature events")
      }
    case _ => executeEvent(event)
  }

  def apply(event:OpenlawVmEvent):OpenlawVm = applyEvent(event) match {
    case Right(result) =>
      result
    case Left(ex) =>
      logger.warn(ex)
      this
  }

  private def processSignature(event:SignatureEvent):Either[String, OpenlawVm] = {
    getAllVariables(IdentityType)
      .map({case (executionResult,variable) => (variable.name, evaluate[Identity](executionResult, variable.name))})
      .filter({
        case (_, Right(identity)) => isSignatureValid(contractId.data, identity, event)
        case _ => false
      }).toList match {
      case Nil =>
        Left("invalid event! no matching identity for the signature")
      case users =>
        val initialValue:Either[String, OpenlawVm] = Right(this)
        users.foldLeft(initialValue)({
          case (Right(currentVm), (_, Right(identity))) =>
            updateContractStateIfNecessary(currentVm.newSignature(identity, event.fullName, event), event)
          case _ =>
            Left("error while processing identity")
        })
    }
  }

  private def updateContractStateIfNecessary(vm:OpenlawVm, event: OpenlawVmEvent):Either[String, OpenlawVm] = {
    vm.executionState match {
      case ContractCreated if vm.allNextActions.isEmpty =>
        vm(UpdateExecutionStateCommand(ContractRunning, event))
      case _ =>
        Right(vm)
    }
  }

  private def executeEvent(event:OpenlawVmEvent):Either[String, OpenlawVm] = oracles.find(_.shouldExecute(event)) match {
    case Some(oracle) =>
      oracle.executeIfPossible(this, event)
    case None =>
      logger.warn(s"no oracle found! for event type ${event.getClass.getSimpleName}")
      Right(this)
  }

  def apply(cmd:OpenlawVmCommand):Either[String, OpenlawVm] = cmd match {
    case LoadTemplateCommand(id, event) =>
      loadTemplate(id, event)
    case UpdateExecutionStateCommand(name, event) =>
      Right(updateExecutionState(name, event))
  }

  private def loadTemplate(id:TemplateId, event:LoadTemplate): Either[String, OpenlawVm] = {
    parser.compileTemplate(event.content).map(template => {
      state = state.updateTemplate(id, template , event)
      this
    })
  }

  private def updateExecutionState(name:ContractExecutionState, event:OpenlawVmEvent):OpenlawVm = {
    state = state.updateExecutionState(name, event)
    this
  }
}

trait OpenlawVmCommand

final case class LoadTemplateCommand(id:TemplateId, event:LoadTemplate) extends OpenlawVmCommand
final case class UpdateExecutionStateCommand(name:ContractExecutionState, event:OpenlawVmEvent) extends OpenlawVmCommand

case class ExecutionScope(template:CompiledTemplate, vm:OpenlawVm, clock:Clock)

abstract sealed class ContractExecutionState(val state:String)

case object ContractCreated extends ContractExecutionState("created")
case object ContractRunning extends ContractExecutionState("running")
case object ContractStopped extends ContractExecutionState("stopped")
case object ContractResumed extends ContractExecutionState("resumed")

object ContractExecutionState {

  def apply(name:String):ContractExecutionState = name match {
    case ContractCreated.state => ContractCreated
    case ContractRunning.state => ContractRunning
    case ContractStopped.state => ContractStopped
    case ContractResumed.state => ContractResumed
  }

  implicit val eqForExecutionState: Eq[ContractExecutionState] = Eq.fromUniversalEquals
}