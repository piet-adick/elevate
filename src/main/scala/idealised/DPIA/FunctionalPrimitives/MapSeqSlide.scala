package idealised.DPIA.FunctionalPrimitives

import idealised.DPIA.Compilation.RewriteToImperative
import idealised.DPIA._
import idealised.DPIA.DSL._
import idealised.DPIA.Types._
import idealised.DPIA.Phrases._
import idealised.DPIA.Phrases.ExpPrimitive
import idealised.DPIA.Semantics.OperationalSemantics
import idealised.DPIA.Semantics.OperationalSemantics.{Store, Data}
import idealised.OpenCL.GlobalMemory

// performs a slide followed by mapSeq while taking advantage of the space/time overlapping reuse opportunity
final case class MapSeqSlide(n: Nat,
                             size: Nat,
                             step: Nat,
                             dt1: DataType,
                             dt2: DataType,
                             f: Phrase[ExpType -> ExpType],
                             input: Phrase[ExpType])
  extends ExpPrimitive
{
  assert(step.eval == 1) // FIXME?
  private val inputSize = step * n + size - step

  override def `type`: ExpType =
    (n: Nat) -> (size: Nat) -> // (step: Nat) ->
      (dt1: DataType) -> (dt2: DataType) ->
      (f :: t"exp[$size.$dt1] -> exp[$dt2]") ->
      (input :: exp"[$inputSize.$dt1]") -> exp"[$n.$dt2]"

  override def visitAndRebuild(v: VisitAndRebuild.Visitor): Phrase[ExpType] = {
    MapSeqSlide(v(n), v(size), v(step), v(dt1), v(dt2),
      VisitAndRebuild(f, v),
      VisitAndRebuild(input, v))
  }

  override def eval(s: Store): Data = {
    val slide = OperationalSemantics.eval(s, Slide(n, size, step, dt1, input))
    OperationalSemantics.eval(s, Map(n, ArrayType(size, dt1), dt2, f, Literal(slide)))
  }

  override def acceptorTranslation(A: Phrase[AccType]): Phrase[CommandType] = {
    import RewriteToImperative._
    import idealised.DPIA.IntermediatePrimitives.{MapSeqSlideIRegRot => I} // TODO: making a choice here

    con(input)(fun(exp"[$inputSize.$dt1]")(x =>
      I(n, size, dt1, dt2,
        fun(exp"[$size.$dt1]")(x =>
          fun(acc"[$dt2]")(o => acc(f(x))(o))),
        x, A
      )))
  }

  override def continuationTranslation(C: Phrase[->[ExpType, CommandType]]): Phrase[CommandType] = {
    import RewriteToImperative._

    `new`(dt"[$n.$dt2]", GlobalMemory, fun(exp"[$n.$dt2]" x acc"[$n.$dt2]")(tmp =>
      acc(this)(tmp.wr) `;` C(tmp.rd)
    ))
  }

  override def prettyPrint: String = s"(mapSeqSlide $n $size $step $f $input)"

  override def xmlPrinter: xml.Elem =
    <mapSeqSlide></mapSeqSlide> // TODO

}