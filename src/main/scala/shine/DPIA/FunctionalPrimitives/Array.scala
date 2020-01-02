package shine.DPIA.FunctionalPrimitives

import shine.DPIA.Compilation.{TranslationContext, TranslationToImperative}
import shine.DPIA.DSL._
import shine.DPIA.Phrases._
import shine.DPIA.Semantics.OperationalSemantics
import shine.DPIA.Types._
import shine.DPIA._

import scala.xml.Elem

final case class Array(dt: DataType,
                       elements: Vector[Phrase[ExpType]])
  extends ExpPrimitive {

  private def tRec(m: Int): ExpType =
    if (m <= 0) { exp"[${elements.length : Nat}.$dt, $read]" }
    else { dt ->: tRec(m - 1) }
  override val t: ExpType = (dt: DataType) ->: tRec(elements.length)

  def prettyPrint: String =
    s"${this.getClass.getSimpleName} (${elements.flatMap(PrettyPhrasePrinter(_))})"

  override def xmlPrinter: Elem =
    <array dt={ToString(dt)}>
       elements.flatMap(Phrases.xmlPrinter(_))
    </array>

  def visitAndRebuild(fun: VisitAndRebuild.Visitor): Phrase[ExpType] =
    Array(fun.data(dt), elements.map(VisitAndRebuild(_, fun)))

  def eval(s: OperationalSemantics.Store): OperationalSemantics.Data = ???

  def acceptorTranslation(A: Phrase[AccType])
                         (implicit context: TranslationContext): Phrase[CommType] = {
    ???
  }

  def continuationTranslation(C: Phrase[ExpType ->: CommType])
                             (implicit context: TranslationContext): Phrase[CommType] = {
    import TranslationToImperative._

    def rec(func: Vector[Phrase[ExpType]], imp: Vector[Phrase[ExpType]]): Phrase[CommType] = {
      func match {
        case xf +: func => con(xf)(fun(exp"[$dt, $read]")(xi =>
          rec(func, imp :+ xi)
        ))
        case _ => C(Array(dt, imp))
      }
    }

    rec(elements, Vector())
  }
}
