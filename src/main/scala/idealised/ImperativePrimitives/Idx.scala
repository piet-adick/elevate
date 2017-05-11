package idealised.ImperativePrimitives

import idealised.Compiling.RewriteToImperative
import idealised._
import idealised.Core._
import idealised.Core.OperationalSemantics._
import idealised.DSL.typed._
import idealised.IntermediatePrimitives.MapI

import scala.xml.Elem

final case class Idx(n: Nat,
                     dt: DataType,
                     index: Phrase[ExpType],
                     array: Phrase[ExpType])
  extends ExpPrimitive {

  override lazy val `type` = exp"[$dt]"

  override def typeCheck(): Unit = {
    import TypeChecker._
    (n: Nat) -> (dt: DataType) ->
      (index :: exp"[idx($n)]") ->
      (array :: exp"[$n.$dt]") ->
      `type`
  }

  override def inferTypes: Idx = {
    import TypeInference._
    val index_ = TypeInference(index)
    val array_ = TypeInference(array)
    (index_.t, array_.t) match {
      case (ExpType(IndexType(n1)), ExpType(ArrayType(n2, dt_))) if n1 == n2 =>
        Idx(n1, dt_, index_, array_)
      case x => error(x.toString, "(exp[idx(n)], exp[n.dt])")
    }
  }

  override def eval(s: Store): Data = {
    (OperationalSemantics.eval(s, array), OperationalSemantics.eval(s, index)) match {
      case (ArrayData(xs), IntData(i)) => xs(i)
      case _ => throw new Exception("This should not happen")
    }
  }

  override def visitAndRebuild(fun: VisitAndRebuild.Visitor): Phrase[ExpType] = {
    Idx(fun(n), fun(dt), VisitAndRebuild(index, fun), VisitAndRebuild(array, fun))
  }

  override def prettyPrint: String = s"(${PrettyPhrasePrinter(array)})[${PrettyPhrasePrinter(index)}]"

  override def xmlPrinter: Elem =
    <idx n={ToString(n)} dt={ToString(dt)}>
      <input type={ToString(ExpType(ArrayType(n, dt)))}>
        {Core.xmlPrinter(array)}
      </input>
      <index type={ToString(ExpType(int))}>
        {Core.xmlPrinter(index)}
      </index>
    </idx>

  override def rewriteToImperativeAcc(A: Phrase[AccType]): Phrase[CommandType] = {
    import RewriteToImperative._
    con(array)(λ(exp"[$n.$dt]")(e =>
      dt match {
        case b: BasicType => A `:=` Idx(n, dt, index, e)
        case ArrayType(m, dt2) =>
          MapI(m, dt2, dt2, λ(ExpType(dt))(e => λ(AccType(dt))(a => acc(e)(a))), Idx(n, dt, index, e), A)
        case RecordType(dt1, dt2) =>
          acc(fst(Idx(n, dt, index, e)))(recordAcc1(dt1, dt2, A)) `;`
            acc(snd(Idx(n, dt, index, e)))(recordAcc2(dt1, dt2, A))
        case _: DataTypeIdentifier => throw new Exception("This should not happen")
      }
    ))
  }

  override def rewriteToImperativeCon(C: Phrase[ExpType -> CommandType]): Phrase[CommandType] =
    RewriteToImperative.con(array)(λ(exp"[$n.$dt]")(e =>
      C(Idx(n, dt, index, e))
    ))
}