package idealised.SurfaceLanguage.Primitives

import idealised.SurfaceLanguage.PrimitiveExpr
import idealised.SurfaceLanguage.Types._
import idealised.SurfaceLanguage._

final case class Cast(dt: BasicType, e: Expr, override val t: Option[BasicType] = None)
  extends PrimitiveExpr {

  override def inferType(subs: TypeInference.SubstitutionMap): Cast = {
    import TypeInference._
    TypeInference(e, subs) |> (e =>
      e.t match {
        case Some(_) => Cast(dt, e, Some(dt))
        case x => error(expr = s"Cast($dt, $e)", found = s"`${x.toString}'", expected = "expr[dt]")
      }
    )
  }

  override def visitAndRebuild(fun: VisitAndRebuild.Visitor): Expr = {
    Cast(fun(dt), VisitAndRebuild(e, fun), t.map(fun(_)))
  }

}