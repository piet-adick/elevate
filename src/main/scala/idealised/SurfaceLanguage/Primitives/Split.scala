package idealised.SurfaceLanguage.Primitives

import idealised.SurfaceLanguage
import idealised.SurfaceLanguage.Types._
import idealised.SurfaceLanguage._

final case class Split(n: Nat, array: Expr,
                       override val t: Option[DataType])
  extends PrimitiveExpr {

  override def inferType(subs: TypeInference.SubstitutionMap): Split = {
    import TypeInference._
    TypeInference(array, subs) |> (array =>
      array.t match {
        case Some(ArrayType(mn, dt)) =>
          Split(n, array, Some(ArrayType(mn /^ n, ArrayType(n, dt))))
        case Some(DepArrayType(mn, NatDependentFunctionType(dt_i, dt))) =>
          val retType =
            DepArrayType(mn /^ n, row =>
              DepArrayType(n, col => Type.substitute(row * n + col, `for` = dt_i, `in` = dt)))
          Split(n, array, Some(retType))
        case x => error(expr = s"Split($n, $array)", found = s"`${x.toString}'", expected = "n.dt")
      })
  }

  override def visitAndRebuild(f: SurfaceLanguage.VisitAndRebuild.Visitor): Expr = {
    Split(f(n), SurfaceLanguage.VisitAndRebuild(array, f), t.map(f(_)))
  }
}
