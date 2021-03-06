package org.adridadou.openlaw.parser.template

import cats.Eq
import org.adridadou.openlaw.parser.template.variableTypes._
import cats.implicits._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.adridadou.openlaw.parser.template.expressions.Expression
import play.api.libs.json.{JsString, Reads, Writes}

case class VariableMember(name:VariableName, keys:Seq[String], formatter:Option[FormatterDefinition]) extends TemplatePart with Expression {
  override def missingInput(executionResult:TemplateExecutionResult): Either[String, Seq[VariableName]] =
    name.aliasOrVariable(executionResult).missingInput(executionResult)

  override def validate(executionResult:TemplateExecutionResult): Option[String] =
    name.aliasOrVariable(executionResult).expressionType(executionResult)
      .validateKeys(name, keys, executionResult)

  override def expressionType(executionResult:TemplateExecutionResult): VariableType =
    name.aliasOrVariable(executionResult).expressionType(executionResult).keysType(keys, executionResult)

  override def evaluate(executionResult:TemplateExecutionResult): Option[Any] = {
    val expr = name.aliasOrVariable(executionResult)
    val exprType = expr.expressionType(executionResult)

    val optValue = expr.evaluate(executionResult)
    optValue.map(exprType.access(_, keys, executionResult) match {
      case Right(value) => value
      case Left(ex) => throw new RuntimeException(ex)
    })
  }

  override def variables(executionResult:TemplateExecutionResult): Seq[VariableName] = {
    val expr = name.aliasOrVariable(executionResult)
    expr.expressionType(executionResult).accessVariables(name, keys, executionResult)
  }
}

case class VariableName(name:String) extends Expression {
  def isAnonymous: Boolean = name.trim === "_"

  override def validate(executionResult:TemplateExecutionResult):Option[String] = None

  override def expressionType(executionResult:TemplateExecutionResult): VariableType = {
    executionResult.getVariable(name) match {
      case Some(variable) => variable.varType(executionResult)
      case None =>
        executionResult.getAlias(name).map(_.expressionType(executionResult)).getOrElse(TextType)
    }
  }

  override def evaluate(executionResult:TemplateExecutionResult):Option[Any] = {
    executionResult.getVariable(name) match {
      case Some(variable) =>
        variable.evaluate(executionResult)
      case None =>
        executionResult.getAlias(name).flatMap(_.evaluate(executionResult))
    }
  }

  override def variables(executionResult:TemplateExecutionResult):Seq[VariableName] =
    executionResult.getExpression(this) match {
      case Some(variable:VariableDefinition) =>
        Seq(this) ++ variable.defaultValue.map(_.variables(executionResult)).getOrElse(Seq())
      case Some(alias:VariableAliasing) =>
        alias.variables(executionResult)
      case Some(expression:Expression) =>
        expression.variables(executionResult)
      case None =>
        Seq(this)
    }

  override def toString:String = name

  override def missingInput(executionResult:TemplateExecutionResult): Either[String, Seq[VariableName]] = executionResult.getAliasOrVariableType(this) map {
    case _:NoShowInForm => Seq()
    case _ =>
      executionResult.getParameter(name) match {
        case Some(_) => Seq()
        case None => executionResult.getAlias(name) match {
          case Some(_) => Seq()
          case None => Seq(this)
        }
      }
  }

  def aliasOrVariable(executionResult:TemplateExecutionResult):Expression =
    executionResult.getAlias(name) match {
      case Some(expr) => expr
      case None => executionResult.getVariable(name) match {
        case Some(variable) => variable
        case None =>
          throw new RuntimeException(s"no alias or variable found with the name $name")
      }
    }

}

case class FormatterDefinition(name:String, parameters:Option[Parameter])

object VariableDefinition {
  def apply(name:String):VariableDefinition =
    VariableDefinition(name = VariableName(name))

  def apply(name:VariableName):VariableDefinition =
    new VariableDefinition(name = name)
}

object VariableName {
  implicit val variableNameEnc: Encoder[VariableName] = deriveEncoder[VariableName]
  implicit val variableNameDec: Decoder[VariableName] = deriveDecoder[VariableName]
  implicit val variableNameEq:Eq[VariableName] = Eq.fromUniversalEquals
  implicit val variableNameJsonWriter:Writes[VariableName] = Writes {name => JsString(name.name)}
  implicit val variableNameJsonReader:Reads[VariableName] = Reads {value => value.validate[String].map(VariableName.apply)}

  def apply(name:String):VariableName = new VariableName(name.trim)
}

case class VariableDefinition(name: VariableName, variableTypeDefinition:Option[VariableTypeDefinition] = None, description:Option[String] = None, formatter:Option[FormatterDefinition] = None, isHidden:Boolean = false, defaultValue:Option[Parameter] = None) extends TemplatePart with Expression with TextElement {

  def isAnonymous: Boolean = name.isAnonymous

  def varType(executionResult: TemplateExecutionResult):VariableType = variableTypeDefinition.flatMap(name => executionResult.findVariableType(name)).getOrElse(TextType)

  def verifyConstructor(executionResult: TemplateExecutionResult): Unit =
    defaultValue.flatMap(parameter => varType(executionResult).construct(parameter, executionResult)).getOrElse("")

  def cast(value: String, executionResult:TemplateExecutionResult): Any =
    varType(executionResult).cast(value, executionResult)

  def nameOnly:Boolean = variableTypeDefinition === None

  override def evaluate(executionResult: TemplateExecutionResult): Option[Any] = {
    executionResult.getAlias(this.name) match {
      case Some(alias) =>
        alias.evaluate(executionResult)
      case None =>
        val optVariable = executionResult.getVariable(this.name)
        executionResult.getAliasOrVariableType(this.name) match {
          case _:NoShowInForm =>
            optVariable
              .flatMap(variable => variable.defaultValue
                .flatMap(constructor => variable.varType(executionResult).construct(constructor, executionResult)))
          case _ =>
            executionResult.getParameter(this.name) match {
              case Some(value) =>
                optVariable.map(_.cast(value, executionResult))
              case None =>
                optVariable
                  .flatMap(variable => variable.defaultValue
                    .flatMap(constructor => variable.varType(executionResult).construct(constructor, executionResult)))
            }
        }
    }
  }

  override def expressionType(executionResult: TemplateExecutionResult): VariableType = executionResult.getAlias(name)
      .map(_.expressionType(executionResult)).getOrElse(executionResult.getVariable(name).map(_.varType(executionResult)) match {
      case Some(vt) =>
        vt
      case None =>
        throw new RuntimeException("the variable or alias '" + name + "' has not been defined. Please define it before using it in an expression")
    })

  override def validate(executionResult: TemplateExecutionResult): Option[String] = None

  override def variables(executionResult: TemplateExecutionResult): Seq[VariableName] =
    name.variables(executionResult)

  override def missingInput(executionResult:TemplateExecutionResult): Either[String, Seq[VariableName]] = {
    val eitherMissing = name.missingInput(executionResult)
    val eitherMissingFromParameters = defaultValue
      .map(getMissingValuesFromParameter(executionResult, _)).getOrElse(Right(Seq()))

    for {
      missing <- eitherMissing
      missingFromParameters <- eitherMissingFromParameters
    } yield missing ++ missingFromParameters
  }


  private def getMissingValuesFromParameter(executionResult:TemplateExecutionResult, param:Parameter):Either[String, Seq[VariableName]] = param match {
    case OneValueParameter(expr) => expr.missingInput(executionResult)
    case ListParameter(exprs) => VariableType.sequence(exprs.map(_.missingInput(executionResult))).map(_.flatten)
    case Parameters(params) => VariableType.sequence(params.map({case (_,value) => getMissingValuesFromParameter(executionResult, value)})).map(_.flatten)
    case MappingParameter(mapping) => VariableType.sequence(mapping.values.map(_.missingInput(executionResult)).toSeq).map(_.flatten)
  }

  override def toString: String = name.name + variableTypeDefinition.map(definition => ":" + definition.name).getOrElse("")
}
