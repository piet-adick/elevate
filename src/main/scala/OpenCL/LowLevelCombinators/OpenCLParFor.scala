package OpenCL.LowLevelCombinators

import Core._
import DSL.typed._
import LowLevelCombinators.AbstractParFor
import OpenCL.Core.{GeneratableComm, ToOpenCL}
import apart.arithmetic._
import opencl.generator.OpenCLAST
import opencl.generator.OpenCLAST.{Block, BlockMember}

abstract class OpenCLParFor(n: ArithExpr,
                            dt: DataType,
                            out: Phrase[AccType],
                            body: Phrase[ExpType -> (AccType -> CommandType)])
  extends AbstractParFor(n, dt, out, body) with GeneratableComm {

  protected var env: ToOpenCL.Environment = null

  protected val name: String = newName()

  def init: ArithExpr
  def step: ArithExpr
  def synchronize: OpenCLAST.OclAstNode with BlockMember

  override def toOpenCL(block: Block, env: ToOpenCL.Environment): Block = {
    import opencl.generator.OpenCLAST._

    this.env = env

    val range = RangeAdd(init, n, step)

    env.ranges(name) = range

    val i = identifier(name, ExpType(int))
    val body_ = Lift.liftFunction( Lift.liftFunction(body)(i) )
    val out_at_i = out `@` i
    TypeChecker(out_at_i)

    val initDecl = VarDecl(name, opencl.ir.Int,
      init = ArithExpression(init),
      addressSpace = opencl.ir.PrivateMemory)

    val cond = CondExpression(VarRef(name),
      ArithExpression(n),
      CondExpression.Operator.<)


    val increment: Expression = {
      val v = NamedVar(name)
      AssignmentExpression(ArithExpression(v), ArithExpression(v + step))
    }

    val bodyBlock = (b: Block) => ToOpenCL.cmd(body_(out_at_i), b, env)

    range.numVals match {
      case Cst(0) =>
        (block: Block) +=
          OpenCLAST.Comment("iteration count is 0, no loop emitted")

      case Cst(1) =>
        (block: Block) +=
          OpenCLAST.Comment("iteration count is exactly 1, no loop emitted")
        (block: Block) += bodyBlock(Block(Vector(initDecl)))

      case _ =>
        if ( (range.start.min.min == Cst(0) && range.stop == Cst(1))
          || (range.numVals.min == Cst(0) && range.numVals.max == Cst(1)) ) {
          (block: Block) +=
            OpenCLAST.Comment("iteration count is 1 or less, no loop emitted")
          val ifthenelse =
            IfThenElse(CondExpression(
              ArithExpression(init),
              ArithExpression(n),
              CondExpression.Operator.<), bodyBlock(Block()))
          (block: Block) += Block(Vector(initDecl, ifthenelse))
        } else {
          (block: Block) +=
            ForLoop(initDecl, cond, increment, bodyBlock(Block()))
        }

    }

    env.ranges.remove(name)

    (block: Block) += synchronize
  }
}