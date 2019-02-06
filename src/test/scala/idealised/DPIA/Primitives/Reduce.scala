package idealised.DPIA.Primitives

import lift.arithmetic._
import idealised.SurfaceLanguage.DSL._
import idealised.SurfaceLanguage.Types._
import idealised.util.{Execute, SyntaxChecker}

class Reduce extends idealised.util.Tests {
  val add = fun(a => fun(b => a + b))

  test("Simple example should generate syntactic valid C code with one loop") {
    val e = fun(ArrayType(SizeVar("N"), float))(a => a :>> reduceSeq(add, 0.0f))

    val p = idealised.C.ProgramGenerator.makeCode(TypeInference(e, Map()).toPhrase)
    val code = p.code
    SyntaxChecker(code)
    println(code)

    "for".r.findAllIn(code).length shouldBe 1
  }

  test("Folding a reduce into a map should generate syntactic valide C code") {
    val e = fun(ArrayType(SizeVar("H"), ArrayType(SizeVar("W"), float)))(a =>
      a :>> map(reduceSeq(add, 0.0f)) :>> mapSeq(fun(x => x))
    )

    val p = idealised.C.ProgramGenerator.makeCode(TypeInference(e, Map()).toPhrase)
    val code = p.code
    SyntaxChecker(code)
    println(code)
  }

  test("Folding a reduce into another should generate syntactic valid C code with two loops") {
    val e = fun(ArrayType(SizeVar("H"), ArrayType(SizeVar("W"), float)))(a =>
      a :>> map(reduceSeq(add, 0.0f)) :>> reduceSeq(add, 0.0f)
    )

    val p = idealised.C.ProgramGenerator.makeCode(TypeInference(e, Map()).toPhrase)
    val code = p.code
    SyntaxChecker(code)
    println(code)

    "for".r.findAllIn(code).length shouldBe 2
  }
}