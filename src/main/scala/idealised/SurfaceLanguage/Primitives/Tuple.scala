package idealised.SurfaceLanguage.Primitives

import idealised.SurfaceLanguage.Types._
import idealised.SurfaceLanguage.{Expr, PrimitiveExpr, VisitAndRebuild}

final case class Tuple(fst: Expr, snd: Expr,
                       override val t: Option[DataType])
  extends PrimitiveExpr {
  
  override def inferType(subs: TypeInference.SubstitutionMap): Tuple = {
    TypeInference(fst, subs) |> (fst =>
      TypeInference(snd, subs) |> (snd =>
        (fst.t, snd.t) match {
          case (Some(ft: DataType), Some(st: DataType)) => Tuple(fst, snd, Some(TupleType(ft, st)))
          case _ => TypeInference.error(this.toString, "")
        }))
  }

  override def visitAndRebuild(f: VisitAndRebuild.Visitor): Expr = {
    Tuple(VisitAndRebuild(fst, f), VisitAndRebuild(snd, f), t.map(f(_)))
  }

}
