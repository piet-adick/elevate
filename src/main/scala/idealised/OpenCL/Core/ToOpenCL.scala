package idealised.OpenCL.Core

import idealised._
import idealised.Core._
import idealised.Core.OperationalSemantics._
import idealised.Compiling._
import idealised.DSL.typed._
import idealised.HighLevelCombinators._
import ir.{Type, UndefType}
import opencl.generator.OpenCLAST._
import idealised.LowLevelCombinators._
import CombinatorsToOpenCL._

import scala.collection._
import scala.collection.immutable.List

class ToOpenCL(val localSize: Nat, val globalSize: Nat) {

  def apply(p: Phrase[ExpType -> ExpType],
            arg: IdentPhrase[ExpType]): Function =
    make(p, arg, List())

  def apply(p: Phrase[ExpType -> (ExpType -> ExpType)],
            arg0: IdentPhrase[ExpType],
            arg1: IdentPhrase[ExpType]): Function =
    make(p, arg0, arg1, List())

  def apply(p: Phrase[ExpType -> (ExpType -> (ExpType -> ExpType))],
            arg0: IdentPhrase[ExpType],
            arg1: IdentPhrase[ExpType],
            arg2: IdentPhrase[ExpType]): Function =
    make(p, arg0, arg1, arg2, List())

  def apply(p: Phrase[ExpType -> (ExpType -> (ExpType -> (ExpType -> ExpType)))],
            arg0: IdentPhrase[ExpType],
            arg1: IdentPhrase[ExpType],
            arg2: IdentPhrase[ExpType],
            arg3: IdentPhrase[ExpType]): Function =
    make(p, arg0, arg1, arg2, arg3, List())

  def apply(p: Phrase[ExpType -> (ExpType -> (ExpType -> (ExpType -> (ExpType -> ExpType))))],
            arg0: IdentPhrase[ExpType],
            arg1: IdentPhrase[ExpType],
            arg2: IdentPhrase[ExpType],
            arg3: IdentPhrase[ExpType],
            arg4: IdentPhrase[ExpType]): Function =
    make(p, arg0, arg1, arg2, arg3, arg4, List())

  private def make(p: Phrase[ExpType],
                   args: List[IdentPhrase[ExpType]]): Function = {
    val p1 = TypeInference(p)
    xmlPrinter.toFile("/tmp/p1.xml", p1)
    TypeChecker(p1)
    val outT = p1.t
    val out = identifier("output", AccType(outT.dataType))
    val params = makeParams(out, args: _*)

    val p2 = RewriteToImperative.acc(p1)(out)
    xmlPrinter.toFile("/tmp/p2.xml", p2)
    TypeChecker(p2)

    val p3 = SubstituteImplementations(p2,
      SubstituteImplementations.Environment(immutable.Map[String, idealised.OpenCL.AddressSpace](("output", OpenCL.GlobalMemory))))
    xmlPrinter.toFile("/tmp/p3.xml", p3)
    TypeChecker(p3)

    val (p4, temporaryAllocations) = HoistMemoryAllocations(p3)
    xmlPrinter.toFile("/tmp/p4.xml", p4)
    TypeChecker(p4)

    val paramsForTemporaryBuffers = makeParams(temporaryAllocations)

    val body = ToOpenCL.cmd(p4, Block(), ToOpenCL.Environment(localSize, globalSize))

    val allocationSizes = computeAllocationSizes(args ++ List(out) ++ temporaryAllocations.map(_._2))
    allocationSizes.foreach { case (name, size) =>
      println(s"Allocating $size for param $name")
    }

    Function(name = "KERNEL", ret = UndefType, params = params ++ paramsForTemporaryBuffers, body = body, kernel = true)
  }

  private def make(p: Phrase[ExpType -> ExpType],
                   arg: IdentPhrase[ExpType],
                   args: List[IdentPhrase[ExpType]]): Function = {
    make(p(arg), arg +: args)
  }

  private def make(p: Phrase[ExpType -> (ExpType -> ExpType)],
                   arg0: IdentPhrase[ExpType],
                   arg1: IdentPhrase[ExpType],
                   args: List[IdentPhrase[ExpType]]): Function = {
    make(p(arg0), arg1, arg0 +: args)
  }

  private def make(p: Phrase[ExpType -> (ExpType -> (ExpType -> ExpType))],
                   arg0: IdentPhrase[ExpType],
                   arg1: IdentPhrase[ExpType],
                   arg2: IdentPhrase[ExpType],
                   args: List[IdentPhrase[ExpType]]): Function = {
    make(p(arg0), arg1, arg2, arg0 +: args)
  }

  private def make(p: Phrase[ExpType -> (ExpType -> (ExpType -> (ExpType -> ExpType)))],
                   arg0: IdentPhrase[ExpType],
                   arg1: IdentPhrase[ExpType],
                   arg2: IdentPhrase[ExpType],
                   arg3: IdentPhrase[ExpType],
                   args: List[IdentPhrase[ExpType]]): Function = {
    make(p(arg0), arg1, arg2, arg3, arg0 +: args)
  }

  private def make(p: Phrase[ExpType -> (ExpType -> (ExpType -> (ExpType -> (ExpType -> ExpType))))],
                   arg0: IdentPhrase[ExpType],
                   arg1: IdentPhrase[ExpType],
                   arg2: IdentPhrase[ExpType],
                   arg3: IdentPhrase[ExpType],
                   arg4: IdentPhrase[ExpType],
                   args: List[IdentPhrase[ExpType]]): Function = {
    make(p(arg0), arg1, arg2, arg3, arg4, arg0 +: args)
  }

  private def makeParams(out: IdentPhrase[AccType],
                         args: IdentPhrase[ExpType]*): List[ParamDecl] = {
    val output = ParamDecl(
      out.name,
      DataType.toType(out.t.dataType),
      opencl.ir.GlobalMemory,
      const = false)

    val inputs = args.map(arg =>
      ParamDecl(
        arg.name,
        DataType.toType(arg.t.dataType),
        opencl.ir.GlobalMemory,
        const = true)
    )

    val types = args.map(_.t.dataType).+:(out.t.dataType).map(DataType.toType)
    val lengths = types.flatMap(Type.getLengths)
    val vars = lengths.filter(_.isInstanceOf[apart.arithmetic.Var]).distinct

    val varDecls = vars.map(v =>
      ParamDecl(v.toString, opencl.ir.Int)
    )

    List(output) ++ inputs ++ varDecls
  }

  private def makeParams(allocations: List[HoistMemoryAllocations.AllocationInfo]): List[ParamDecl] = {
    allocations.map { case (addressSpace, identifier) =>
      ParamDecl(
        identifier.name,
        DataType.toType(identifier.t.t1.dataType),
        OpenCL.AddressSpace.toOpenCL(addressSpace),
        const = false
      )
    }
  }

  private def computeAllocationSizes(params: List[IdentPhrase[_]]): immutable.Map[String, SizeInByte] = {
    params.map( i => {
      val dt = i.t match {
        case ExpType(dataType) => dataType
        case AccType(dataType) => dataType
        case PairType(ExpType(dt1), AccType(dt2)) if dt1 == dt2 => dt1
        case _ => throw new Exception("This should not happen")
      }
//      println(s"get size in bytes for $dt")
      (i.name, DataType.sizeInByte(dt))
    }).toMap
  }

}

object ToOpenCL {

  case class Environment(localSize: Nat,
                         globalSize: Nat,
                         ranges: mutable.Map[String, apart.arithmetic.Range])

  object Environment {
    def apply(localSize: Nat, globalSize: Nat): Environment = {
      Environment(localSize, globalSize,
        mutable.Map[String, apart.arithmetic.Range]())
    }
  }

  def cmd(p: Phrase[CommandType], block: Block, env: Environment): Block = {
    p match {
      case IfThenElsePhrase(condP, thenP, elseP) =>
        val trueBlock = cmd(thenP, Block(), env)
        val falseBlock = cmd(elseP, Block(), env)
        (block: Block) += IfThenElse(exp(condP, env), trueBlock, falseBlock)

      case c: GeneratableComm => c.toOpenCL(block, env)

      case a: Assign => toOpenCL(a, block, env)
      case d: DoubleBufferFor => toOpenCL(d, block, env)
      case f: For => toOpenCL(f, block, env)
      case n: New => toOpenCL(n, block, env)
      case s: idealised.LowLevelCombinators.Seq => toOpenCL(s, block, env)
      case s: idealised.LowLevelCombinators.Skip => toOpenCL(s, block, env)

      case p: ParFor => toOpenCL(p, block, env)

      case ApplyPhrase(_, _) | NatDependentApplyPhrase(_, _) |
           TypeDependentApplyPhrase(_, _) | IdentPhrase(_, _) |
           Proj1Phrase(_) | Proj2Phrase(_) |
           _: MidLevelCombinator | _: LowLevelCommCombinator =>
        throw new Exception(s"Don't know how to generate idealised.OpenCL code for $p")
    }
  }

  def exp(p: Phrase[ExpType], env: Environment): Expression = {
    p match {
      case BinOpPhrase(op, lhs, rhs) =>
        BinaryExpression(op.toString, exp(lhs, env), exp(rhs, env))
      case IdentPhrase(name, _) => VarRef(name)
      case LiteralPhrase(d, _) =>
        d match {
          case i: IntData => Literal(i.i.toString)
          case b: BoolData => Literal(b.b.toString)
          case f: FloatData => Literal(f.f.toString)
          case i: IndexData => Literal(i.i.toString)
          case v: VectorData => Literal(Data.toString(v))
          case _: RecordData => ???
          case _: ArrayData => ???
        }
      case p: Proj1Phrase[ExpType, _] => exp(Lift.liftPair(p.pair)._1, env)
      case p: Proj2Phrase[_, ExpType] => exp(Lift.liftPair(p.pair)._2, env)
      case UnaryOpPhrase(op, x) =>
        UnaryExpression(op.toString, exp(x, env))

      case f: Fst => toOpenCL(f, env, List(), List(), f.t.dataType)
      case i: Idx => toOpenCL(i, env, List(), List(), i.t.dataType)
      case r: Record => toOpenCL(r, env, List(), List(), r.t.dataType)
      case s: Snd => toOpenCL(s, env, List(), List(), s.t.dataType)
      case t: TruncExp => toOpenCL(t, env, List(), List(), t.t.dataType)

      case ApplyPhrase(_, _) | NatDependentApplyPhrase(_, _) |
           TypeDependentApplyPhrase(_, _) | IfThenElsePhrase(_, _, _) |
           _: HighLevelCombinator | _: LowLevelExpCombinator =>
        throw new Exception(s"Don't know how to generate idealised.OpenCL code for $p")
    }
  }

  def exp(p: Phrase[ExpType],
          env: Environment,
          arrayAccess: List[(Nat, Nat)],
          tupleAccess: List[Nat],
          dt: DataType): Expression = {
    p match {
      case IdentPhrase(name, t) =>
        val i = arrayAccess.map(x => x._1 * x._2).foldLeft(0: Nat)((x, y) => x + y)
        val index = if (i != (0: Nat)) {
          i
        } else {
          null
        }

        val s = tupleAccess.map {
          case apart.arithmetic.Cst(1) => "._1"
          case apart.arithmetic.Cst(2) => "._2"
          case _ => throw new Exception("This should not happen")
        }.foldLeft("")(_ + _)

        val suffix = if (s != "") {
          s
        } else {
          null
        }

        val originalType = t.dataType
        val currentType = dt

        (originalType, currentType) match {
          case (ArrayType(_, st1), VectorType(n, st2)) if st1 == st2 && st1.isInstanceOf[BasicType] =>
            VLoad(
              VarRef(name), DataType.toType(VectorType(n, st2)).asInstanceOf[ir.VectorType],
              ArithExpression(index))
          case _ =>
            VarRef(name, suffix, ArithExpression(index))
        }

      case p: Proj1Phrase[ExpType, _] => exp(Lift.liftPair(p.pair)._1, env, arrayAccess, tupleAccess, dt)
      case p: Proj2Phrase[_, ExpType] => exp(Lift.liftPair(p.pair)._2, env, arrayAccess, tupleAccess, dt)

      case v: ViewExp => v.toOpenCL(env, arrayAccess, tupleAccess, dt)

      case g: Gather => toOpenCL(g, env, arrayAccess, tupleAccess, dt)
      case j: Join => toOpenCL(j, env, arrayAccess, tupleAccess, dt)
      case s: Split => toOpenCL(s, env, arrayAccess, tupleAccess, dt)
      case z: Zip => toOpenCL(z, env, arrayAccess, tupleAccess, dt)

      case f: Fst => toOpenCL(f, env, arrayAccess, tupleAccess, dt)
      case i: Idx => toOpenCL(i, env, arrayAccess, tupleAccess, dt)
      case r: Record => toOpenCL(r, env, arrayAccess, tupleAccess, dt)
      case s: Snd => toOpenCL(s, env, arrayAccess, tupleAccess, dt)
      case t: TruncExp => toOpenCL(t, env, arrayAccess, tupleAccess, dt)

      case ApplyPhrase(_, _) | NatDependentApplyPhrase(_, _) |
           TypeDependentApplyPhrase(_, _) |
           BinOpPhrase(_, _, _) | UnaryOpPhrase(_, _) |
           IfThenElsePhrase(_, _, _) | LiteralPhrase(_, _) |
           _: LowLevelExpCombinator | _: HighLevelCombinator =>
        throw new Exception(s"Don't know how to generate idealised.OpenCL code for $p")
    }
  }


  def acc(p: Phrase[AccType], env: Environment): VarRef = {
    p match {
      case IdentPhrase(name, _) => VarRef(name)
      case p: Proj1Phrase[AccType, _] => acc(Lift.liftPair(p.pair)._1, env)
      case p: Proj2Phrase[_, AccType] => acc(Lift.liftPair(p.pair)._2, env)

      case f: FstAcc => toOpenCL(f, env, List(), List(), f.t.dataType)
      case i: IdxAcc => toOpenCL(i, env, List(), List(), i.t.dataType)
      case j: JoinAcc => toOpenCL(j, env, List(), List(), j.t.dataType)
      case r: RecordAcc => toOpenCL(r, env, List(), List(), r.t.dataType)
      case s: SndAcc => toOpenCL(s, env, List(), List(), s.t.dataType)
      case s: SplitAcc => toOpenCL(s, env, List(), List(), s.t.dataType)
      case t: TruncAcc => toOpenCL(t, env, List(), List(), t.t.dataType)

      case ApplyPhrase(_, _) | NatDependentApplyPhrase(_, _) |
           TypeDependentApplyPhrase(_, _) | IfThenElsePhrase(_, _, _) |
           _: LowLevelAccCombinator =>
        throw new Exception(s"Don't know how to generate idealised.OpenCL code for $p")
    }
  }

  def acc(p: Phrase[AccType],
          env: Environment,
          arrayAccess: List[(Nat, Nat)],
          tupleAccess: List[Nat],
          dt: DataType): VarRef = {
    p match {
      case IdentPhrase(name, t) =>
        val i = arrayAccess.map(x => x._1 * x._2).foldLeft(0: Nat)((x, y) => x + y)
        val index = if (i != (0: Nat)) {
          i
        } else {
          null
        }

        val s = tupleAccess.map {
          case apart.arithmetic.Cst(1) => "._1"
          case apart.arithmetic.Cst(2) => "._2"
          case _ => throw new Exception("This should not happen")
        }.foldLeft("")(_ + _)

        val suffix = if (s != "") {
          s
        } else {
          null
        }

        val originalType = t.dataType
        val currentType = dt

        (originalType, currentType) match {
          case (ArrayType(_, st1), VectorType(n, st2)) if st1 == st2 && st1.isInstanceOf[BasicType] =>
            // TODO: can we turn this into a vload => need the value for this ...
            // TODO: figure out addressspace of identifier name
            VarRef(s"((/*the addressspace is hardcoded*/global $currentType*)$name)", suffix, ArithExpression(index))
          case _ =>
            VarRef(name, suffix, ArithExpression(index))
        }

      case v: ViewAcc => v.toOpenCL(env, arrayAccess, tupleAccess, dt)

      case f: FstAcc => toOpenCL(f, env, arrayAccess, tupleAccess, dt)
      case i: IdxAcc => toOpenCL(i, env, arrayAccess, tupleAccess, dt)
      case j: JoinAcc => toOpenCL(j, env, arrayAccess, tupleAccess, dt)
      case r: RecordAcc => toOpenCL(r, env, arrayAccess, tupleAccess, dt)
      case s: SndAcc => toOpenCL(s, env, arrayAccess, tupleAccess, dt)
      case s: SplitAcc => toOpenCL(s, env, arrayAccess, tupleAccess, dt)
      case t: TruncAcc => toOpenCL(t, env, arrayAccess, tupleAccess, dt)

      case p: Proj1Phrase[AccType, _] => acc(Lift.liftPair(p.pair)._1, env, arrayAccess, tupleAccess, dt)
      case p: Proj2Phrase[_, AccType] => acc(Lift.liftPair(p.pair)._2, env, arrayAccess, tupleAccess, dt)

      case ApplyPhrase(_, _) | NatDependentApplyPhrase(_, _) |
           TypeDependentApplyPhrase(_, _) | IfThenElsePhrase(_, _, _) |
           _: LowLevelAccCombinator =>
        throw new Exception(s"Don't know how to generate idealised.OpenCL code for $p")
    }
  }

}