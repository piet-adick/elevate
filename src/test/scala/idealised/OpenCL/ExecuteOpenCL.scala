package idealised.OpenCL

import idealised.OpenCL.SurfaceLanguage.DSL.mapGlobal
import idealised.SurfaceLanguage.DSL.{fun, mapSeq, _}
import idealised.SurfaceLanguage.Types._
import idealised.SurfaceLanguage.{->, Expr, NatIdentifier, `(nat)->`}
import idealised.util.SyntaxChecker

import scala.language.postfixOps
import scala.language.reflectiveCalls


class ExecuteOpenCL extends idealised.util.TestsWithExecutor {
  test("Running a simple kernel with generic input size") {
    val f: Expr[`(nat)->`[DataType -> DataType]] =
      dFun((n: NatIdentifier) => fun(ArrayType(n, int))(xs => xs :>> mapSeq(fun(x => x + 1))))

    val kernel = idealised.OpenCL.KernelGenerator.makeCode(TypeInference(f, Map()).toPhrase)
    println(kernel.code)
    SyntaxChecker.checkOpenCL(kernel.code)

    val kernelF = kernel.as[ScalaFunction`(`Int`,`Array[Int]`)=>`Array[Int]].withSizes(1, 1)
    val xs = Array.fill(8)(0)

    val (result, time) = kernelF(8`,`xs)
    println(time)

    val gold = Array.fill(8)(1)
    assertResult(gold)(result)
  }

  test("Running a simple kernel with fixed input size") {
    val n = 8
    val f: Expr[DataType -> DataType] =
      fun(ArrayType(n, int))(xs => xs :>> mapSeq(fun(x => x + 1)))

    val kernel = idealised.OpenCL.KernelGenerator.makeCode(TypeInference(f, Map()).toPhrase)
    println(kernel.code)
    SyntaxChecker.checkOpenCL(kernel.code)

    val kernelF = kernel.as[ScalaFunction`(`Array[Int]`)=>`Array[Int]].withSizes(1, 1)
    val xs = Array.fill(n)(0)

    val (result, time) = kernelF(xs`;`)
    println(time)

    val gold = Array.fill(n)(1)
    assertResult(gold)(result)
  }

  test("Running a simple kernel with multiple generic input sizes") {
    val m = 4
    val n = 8
    val f: Expr[`(nat)->`[`(nat)->`[DataType -> DataType]]] =
      dFun((m: NatIdentifier) =>
        dFun((n: NatIdentifier) =>
          fun(ArrayType(m, ArrayType(n, int)))(xs =>
            xs :>> mapSeq(mapSeq(fun(x => x + 1))))))

    val kernel = idealised.OpenCL.KernelGenerator.makeCode(TypeInference(f, Map()).toPhrase)
    println(kernel.code)
    SyntaxChecker.checkOpenCL(kernel.code)

    val kernelF = kernel.as[ScalaFunction`(`Int`,`Int`,`Array[Array[Int]]`)=>`Array[Int]].withSizes(1, 1)
    val xs = Array.fill(m)(Array.fill(n)(0))

    val (result, time) =  kernelF(m`,`n`,`xs)
    println(time)

    val gold = Array.fill(m)(Array.fill(n)(1)).flatten
    assertResult(gold)(result)
  }

  test("Running a simple kernel mixing nat-dependent with normal functions") {
    val n = 8
    val s = 2
    val mult = fun(t => t._1 * t._2)
    val f: Expr[`(nat)->`[DataType -> `(nat)->`[DataType]]] =
      dFun((n: NatIdentifier) =>
        fun(ArrayType(n, int))(xs =>
          dFun((s: NatIdentifier) =>
            xs :>> split(s) :>> mapSeq(mapSeq(fun(x => x + 1))) :>> join())))

    val kernel = idealised.OpenCL.KernelGenerator.makeCode(TypeInference(f, Map()).toPhrase)
    println(kernel.code)
    SyntaxChecker.checkOpenCL(kernel.code)

    val kernelF = kernel.as[ScalaFunction`(`Int`,`Array[Int]`,`Int`)=>`Array[Int]]//(1, 1)

    val xs = Array.fill(n)(2)
    val (result, time) =  kernelF(1,1)(n`,`xs`,`s)

    val gold = xs.map(x => x + 1)
    assertResult(gold)(result)
  }

  test("Running a simple kernel with fixed input size and multiple threads") {
    val n = 8
    val f: Expr[DataType -> DataType] =
      fun(ArrayType(n, int))(xs => xs :>> mapGlobal(fun(x => x + 1)))

    val kernel = idealised.OpenCL.KernelGenerator.makeCode(TypeInference(f, Map()).toPhrase)
    println(kernel.code)
    SyntaxChecker.checkOpenCL(kernel.code)

    val kernelF = kernel.as[ScalaFunction`(`Array[Int]`)=>`Array[Int]].withSizes(1, 1)
    val xs = Array.fill(n)(0)

    val (result, time) =  kernelF(xs`;`)
    println(time)

    val gold = Array.fill(n)(1)
    assertResult(gold)(result)
  }
}
