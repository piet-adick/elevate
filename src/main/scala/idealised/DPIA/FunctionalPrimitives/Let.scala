package idealised.DPIA.FunctionalPrimitives

import idealised.DPIA.Compilation.TranslationContext
import idealised.DPIA._
import idealised.DPIA.DSL._
import idealised.DPIA.Types._
import idealised.DPIA.Phrases._
import idealised.DPIA.Semantics.OperationalSemantics
import idealised.DPIA.Semantics.OperationalSemantics.Store

import scala.xml.Elem

final case class Let(dt1: DataType, dt2: DataType,
                     value: Phrase[ExpType],
                     f: Phrase[ExpType ->: ExpType])
  extends ExpPrimitive
{
  override val t: ExpType =
    (dt1: DataType) ->: (dt2: DataType) ->:
      (value :: exp"[$dt1, $read]") ->:
      (f :: t"exp[$dt1, $read] -> exp[$dt2, $read]") ->:
      exp"[$dt2, $read]"

  override def visitAndRebuild(v: VisitAndRebuild.Visitor): Phrase[ExpType] =
    Let(v.data(dt1), v.data(dt2),
      VisitAndRebuild(value, v),
      VisitAndRebuild(f, v))

  override def eval(s: Store): OperationalSemantics.Data = ???

  override def acceptorTranslation(A: Phrase[AccType])(implicit context: TranslationContext): Phrase[CommType] = {
    import idealised.DPIA.Compilation.TranslationToImperative._
    con(value)(fun(value.t)(x => acc(f(x))(A)))
  }

  override def continuationTranslation(C: Phrase[ExpType ->: CommType])(implicit context: TranslationContext): Phrase[CommType] = {
    import idealised.DPIA.Compilation.TranslationToImperative._
    con(value)(fun(value.t)(x => con(f(x))(C)))
  }

  override def prettyPrint: String = s"(let ${PrettyPhrasePrinter(value)} ${PrettyPhrasePrinter(f)})"

  override def xmlPrinter: Elem =
    <let dt1={ToString(dt1)} dt2={ToString(dt2)}>
      <value>{Phrases.xmlPrinter(value)}</value>
      <f>{Phrases.xmlPrinter(f)}</f>
    </let>
}
