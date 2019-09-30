package idealised.OpenCL

import idealised.C.AST.ParamDecl
import idealised.DPIA.Phrases.Identifier
import idealised.DPIA.Types._
import idealised.DPIA._
import idealised.{C, OpenCL}
import lift.arithmetic._
import opencl.executor._
import util.{Time, TimeSpan}

import scala.collection.immutable.List
import scala.collection.{Seq, immutable}
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

//noinspection ScalaDocParserErrorInspection
case class Kernel(decls: Seq[C.AST.Decl],
                  kernel: OpenCL.AST.KernelDecl,
                  outputParam: Identifier[AccType],
                  inputParams: Seq[Identifier[ExpType]],
                  intermediateParams: Seq[Identifier[VarType]]) {

  def code: String = decls.map(OpenCL.AST.Printer(_)).mkString("\n") +
    "\n\n" +
    OpenCL.AST.Printer(kernel)

  /** This method will return a Scala function which executed the kernel via OpenCL and returns its
  // result and the time it took to execute the kernel.
  //
  // A type annotation `F` has to be provided which specifies the type of the Scala function.
  // The following syntax is used for this (which implementation can be found in
  // OpenCL/Types.scala):
  //
  // <kernel>.as[ScalaFunction `(` <Arg1Type> `,` <Arg2Type> `,` <...> `)=>` <ReturnType> ]
  //
  // where all text in < > is to be replaced with the appropriate Scala terms.
  // An example for the dot product which takes two arrays of floats and returns an array of float
  // would be:
  //
  // dotKernel.as[ScalaFunction `(` Array[Float] `,` Array[Float] `)=>` Array[Float]]

    NB: If the kernel takes a single argument, then the invocation must use this syntax
    (Example with a kernel of type Array[Float] => Array[Float]

    val kernelF = kernel.as[ScalaFunction`(`Array[Float]`)=>`Array[Float]]
    val (result, time) = kernelF(xs `;`)
    */
  def as[F <: FunctionHelper](localSize: LocalSize, globalSize: GlobalSize)
                             (implicit ev: F#T <:< HList): F#T => (F#R, TimeSpan[Time.ms]) = {
    hArgs: F#T => {
      val args: List[Any] = hArgs.toList
      assert(inputParams.length == args.length)
      val (sizeVarMap, outputArg, inputArgs) = createKernelArgs(inputParams zip args, intermediateParams)
      val kernelArgs = outputArg :: inputArgs

      val kernelJNI = opencl.executor.Kernel.create(code, kernel.name, "")

      List(localSize match {
        case LocalSize(x) if !x.isEvaluable => Some(s"OpenCL local size is not evaluable (currently set to $x)")
        case _ => None
      }, globalSize match {
        case GlobalSize(x) if !x.isEvaluable => Some(s"OpenCL local size is not evaluable (currently set to $x)")
        case _ => None
      }).filter(_.isDefined).map(_.get) match {
        case Nil =>
        case problems =>
          val errorMessage = "Cannot run kernel:\n" ++ problems.reduce(_ ++ _)
          throw new Exception(errorMessage)
      }

      val lengthMapping = sizeVarMap.asInstanceOf[Map[Nat,Nat]]
      val runtime = Executor.execute(kernelJNI,
        ArithExpr.substitute(localSize.size.x, lengthMapping).eval,
        ArithExpr.substitute(localSize.size.y, lengthMapping).eval,
        ArithExpr.substitute(localSize.size.z, lengthMapping).eval,
        ArithExpr.substitute(globalSize.size.x, lengthMapping).eval,
        ArithExpr.substitute(globalSize.size.y, lengthMapping).eval,
        ArithExpr.substitute(globalSize.size.z, lengthMapping).eval,
        kernelArgs.toArray
        )

      val output = castToOutputType[F#R](outputParam.`type`.dataType, outputArg)

      kernelArgs.foreach(_.dispose)
      kernelJNI.dispose()

      (output, TimeSpan.inMilliseconds(runtime))
    }
  }

  /**
    * A helper class to group together the various bits of related information that make up a parameter
    * @param identifier The identifier representing the parameter in the dpia source
    * @param parameter The OpenCL parameter as appearing in the generated kernel
    * @param argValue For non-intermediate input parameters, this carries the scala value to pass in the executor
    */
  private case class Argument(identifier: Identifier[ExpType], parameter: ParamDecl, argValue: Option[Any])

  private def constructArguments(inputs: Seq[(Identifier[ExpType], Any)],
                                 intermediateParams: Seq[Identifier[VarType]],
                                 oclParams: Seq[ParamDecl]): List[Argument] = {
    // For each input ...
    inputs.headOption match {
          // ... if we have no more, we look at the intermediates ...
        case None => intermediateParams.headOption match {
          // ... if we have no more, we should also be out of ParamDecls
          case None =>
            assert(oclParams.isEmpty)
            Nil
          case Some(param) =>
            // We must have an OpenCl parameter
            assert(oclParams.nonEmpty, "Not enough opencl parameters")
            Argument(Identifier(param.name, param.t.t1), oclParams.head, None) ::
              constructArguments(inputs, intermediateParams.tail, oclParams.tail)
        }
        case Some((param, arg)) =>
          // We must have an OpenCl parameter
          assert(oclParams.nonEmpty, "Not enough opencl parameters")
          Argument(param, oclParams.head, Some(arg)) :: constructArguments(inputs.tail, intermediateParams, oclParams.tail)
      }
  }

  /**
    * From the dpia paramters and the scala arguments, returns a Map of identifiers to sizes for "size vars",
    * the output kernel argument, and all the input kernel arguments
   */
  private def createKernelArgs(inputs: Seq[(Identifier[ExpType], Any)],
                               intermediateParameters: Seq[Identifier[VarType]]
                              ):(Map[NatIdentifier, Nat], GlobalArg, List[KernelArg]) = {
    //Match up corresponding dpia parameter/intermediate parameter/scala argument (when present) in a covenience
    //structure
    val arguments = constructArguments(inputs, intermediateParameters, this.kernel.params.tail)

    //First, we want to find all the parameter mappings
    val sizeVarMapping = collectSizeVars(arguments, Map())
    //Now generate the input kernel args
    println(s"Create input arguments")
    val kernelArguments = createInputKernelArgs(arguments, sizeVarMapping)
    //Finally, create the output
    println(s"Create output argument")
    val kernelOutput = createOutputKernelArg(sizeVarMapping)

    (sizeVarMapping, kernelOutput, kernelArguments)
  }

  /**
   * Iterates through all the input arguments, collecting the values of all int-typed input parameters, which may appear
    * in the sizes of the other arguments.
   */
  private def collectSizeVars(arguments:List[Argument], sizeVariables:Map[NatIdentifier, Nat]):Map[NatIdentifier, Nat] = {
    def recordSizeVariable( sizeVariables:Map[NatIdentifier, Nat], arg:Argument) = {
      arg.identifier.t match {
        case ExpType(idealised.DPIA.Types.int, read) =>
          arg.argValue match {
            case Some(i:Int) => sizeVariables + ((NatIdentifier(arg.identifier.name), Cst(i)))
            case Some(num) =>
              throw new Exception(s"Int value for kernel argument ${arg.identifier.name} expected but $num (of type ${num.getClass.getName} found")
            case None =>
              throw new Exception("Int kernel parameter needs a value")
          }
        case _ => sizeVariables
      }
    }

    arguments.foldLeft(Map[NatIdentifier, Nat]())(recordSizeVariable)
  }

  private def createLocalArg(sizeInByte: Long): LocalArg = {
    println(s"Allocated local argument with $sizeInByte bytes")
    LocalArg.create(sizeInByte)
  }

  private def createGlobalArg(sizeInByte: Long): GlobalArg = {
    println(s"Allocated global argument with $sizeInByte bytes")
    GlobalArg.createOutput(sizeInByte)
  }

  private def createGlobalArg(array: Array[Float]): GlobalArg = {
    println(s"Allocated global argument with ${array.length * 4} bytes")
    GlobalArg.createInput(array)
  }

  private def createGlobalArg(array: Array[Int]): GlobalArg = {
    println(s"Allocated global argument with ${array.length * 4} bytes")
    GlobalArg.createInput(array)
  }

  private def createGlobalArg(array: Array[Double]): GlobalArg = {
    println(s"Allocated global argument with ${array.length * 8} bytes")
    GlobalArg.createInput(array)
  }

  private def createValueArg(value: Float): ValueArg = {
    println(s"Allocated value argument with 4 bytes")
    ValueArg.create(value)
  }

  private def createValueArg(value: Int): ValueArg = {
    println(s"Allocated value argument with 4 bytes")
    ValueArg.create(value)
  }

  private def createValueArg(value: Double): ValueArg = {
    println(s"Allocated value argument with 8 bytes")
    ValueArg.create(value)
  }

  /**
    * Generates the input kernel args
    * @param arguments
    * @param sizeVariables
    * @return
    */
  private def createInputKernelArgs(arguments:List[Argument], sizeVariables:Map[NatIdentifier, Nat]):List[KernelArg] = {

    //Helper for the creation of intermdiate arguments
    def createIntermediateArg(arg:Argument, sizeVariables:Map[NatIdentifier, Nat]):KernelArg = {
      //Get the size of bytes, potentially with free variables
      val rawSize = sizeInByte(arg.identifier.t.dataType)
      //Try to substitue away all the free variables
      val cleanSize = ArithExpr.substitute(rawSize.value, sizeVariables.asInstanceOf[Map[Nat, Nat]])
      Try(cleanSize.evalLong) match {
        case Success(actualSize) =>
          arg.parameter.t match {
            case OpenCL.AST.PointerType(a, _, _) => a match {
              case AddressSpace.Private => throw new Exception ("'Private memory' is an invalid memory for opencl parameter")
              case AddressSpace.Local => createLocalArg(actualSize)
              case AddressSpace.Global =>  createGlobalArg(actualSize)
              case AddressSpace.Constant => ???
              case AddressSpaceIdentifier(_) => throw new Exception ("This shouldn't happen")
            }
            case _ => throw new Exception ("This shouldn't happen")
          }
        case Failure(_) => throw new Exception(s"Could not evaluate $cleanSize")
      }
    }

    arguments match {
      case Nil => Nil
      case arg :: remainingArgs =>
        val kernelArg = arg.argValue match {
            //We have a scala value - this is an input argument
          case Some(scalaValue) => createInputArgFromScalaValue(scalaValue)
            //No scala value - this is an intermediate argument
          case None => createIntermediateArg(arg, sizeVariables)
        }
        kernelArg::createInputKernelArgs(remainingArgs, sizeVariables)
    }
  }

  private def createOutputKernelArg(sizeVariables:Map[NatIdentifier,Nat]):GlobalArg = {
    val rawSize = sizeInByte(this.outputParam.t.dataType).value
    val cleanSize = ArithExpr.substitute(rawSize, sizeVariables.asInstanceOf[Map[Nat,Nat]])
    Try(cleanSize.evalLong) match {
      case Success(actualSize) => createGlobalArg(actualSize)
      case Failure(_) => throw new Exception(s"Could not evaluate $cleanSize")
    }
  }

  private def createInputArgFromScalaValue(arg: Any): KernelArg = {
    arg match {
      case  f: Float => createValueArg(f)
      case af: Array[Float] => createGlobalArg(af)
      case af: Array[Array[Float]] => createGlobalArg(af.flatten)
      case af: Array[Array[Array[Float]]] => createGlobalArg(af.flatten.flatten)
      case af: Array[Array[Array[Array[Float]]]] => createGlobalArg(af.flatten.flatten.flatten)

      case  i: Int => createValueArg(i)
      case ai: Array[Int] => createGlobalArg(ai)
      case ai: Array[Array[Int]] => createGlobalArg(ai.flatten)
      case ai: Array[Array[Array[Int]]] => createGlobalArg(ai.flatten.flatten)
      case ai: Array[Array[Array[Array[Int]]]] => createGlobalArg(ai.flatten.flatten.flatten)

      case  d: Double => createValueArg(d)
      case ad: Array[Double] => createGlobalArg(ad)
      case ad: Array[Array[Double]] => createGlobalArg(ad.flatten)
      case ad: Array[Array[Array[Double]]] => createGlobalArg(ad.flatten.flatten)
      case ad: Array[Array[Array[Array[Double]]]] => createGlobalArg(ad.flatten.flatten.flatten)

      case p: Array[(_, _)] => p.head match {
          case (_: Int, _: Float) =>
            GlobalArg.createInput(flattenToArrayOfInts(p.asInstanceOf[Array[(Int, Float)]]))
        }
      case pp: Array[Array[(_, _)]] => pp.head.head match {
        case (_: Int, _: Float) =>
          GlobalArg.createInput(pp.flatMap(a => flattenToArrayOfInts(a.asInstanceOf[Array[(Int, Float)]])))
      }

      case _ => throw new IllegalArgumentException("Kernel argument is of unsupported type: " +
        arg.getClass.getName)
    }
  }

  private def flattenToArrayOfInts(a: Array[(Int, Float)]): Array[Int] = {
    a.flatMap{ case (x,y) => Iterable(x, java.lang.Float.floatToIntBits(y)) }
  }

  private def castToOutputType[R](dt: DataType, output: GlobalArg): R = {
    assert(dt.isInstanceOf[ArrayType] || dt.isInstanceOf[DepArrayType])
    (DataType.getBaseDataType(dt) match {
      case idealised.DPIA.Types.float  => output.asFloatArray()
      case idealised.DPIA.Types.int    => output.asIntArray()
      case idealised.DPIA.Types.double => output.asDoubleArray()
      // TODO: generalize
      case idealised.DPIA.Types.RecordType(idealised.DPIA.Types.float4, idealised.DPIA.Types.float4) => output.asFloatArray()
      case idealised.DPIA.Types.RecordType(idealised.DPIA.Types.float, idealised.DPIA.Types.float) => output.asFloatArray()
      case idealised.DPIA.Types.float4 => output.asFloatArray()
      case _ => throw new IllegalArgumentException("Return type of the given lambda expression " +
        "not supported: " + dt.toString)
    }).asInstanceOf[R]
  }

  private implicit def getDataType(i: Identifier[_]): DataType = {
    i.t match {
      case ExpType(dataType, _) => dataType
      case AccType(dataType) => dataType
      case PairType(ExpType(dt1, _), AccType(dt2)) if dt1 == dt2 => dt1
      case _ => throw new Exception("This should not happen")
    }
  }

  private def sizeInByte(dt: DataType): SizeInByte = dt match {
    case s: ScalarType => s match {
      case idealised.DPIA.Types.bool    => SizeInByte(1)
      case idealised.DPIA.Types.int | idealised.DPIA.Types.NatType => SizeInByte(4)
      case idealised.DPIA.Types.float   => SizeInByte(4)
      case idealised.DPIA.Types.double  => SizeInByte(8)
    }
    case _: IndexType   => SizeInByte(4) // == sizeof(int)
    case v: VectorType  => sizeInByte(v.elemType) * v.size
    case r: RecordType  => sizeInByte(r.fst) + sizeInByte(r.snd)
    case a: ArrayType   => sizeInByte(a.elemType) * a.size
    case a: DepArrayType =>
      a.elemFType match {
        case NatToDataLambda(x, body) =>
          SizeInByte(BigSum(Cst(0), a.size - 1, `for`=x, in=sizeInByte(body).value))
        case _: NatToDataIdentifier =>
          throw new Exception("This should not happen")
      }
    case _: NatToDataApply =>  throw new Exception("This should not happen")
    case _: DataTypeIdentifier => throw new Exception("This should not happen")
  }

  case class SizeInByte(value: Nat) {
    def *(rhs: Nat) = SizeInByte(value * rhs)
    def +(rhs: SizeInByte) = SizeInByte(value + rhs.value)

    override def toString = s"$value bytes"
  }

  private implicit class SubstitutionsHelper(size: SizeInByte) {
    def `with`(valueMap: immutable.Map[Nat, Nat]): SizeInByte = {
      SizeInByte(ArithExpr.substitute(size.value, valueMap))
    }
  }
}

sealed case class KernelWithSizes(kernel: Kernel,
                                  localSize: LocalSize,
                                  globalSize: GlobalSize) {
  def as[F <: FunctionHelper](implicit ev: F#T <:< HList): F#T => (F#R, TimeSpan[Time.ms]) =
    kernel.as[F](localSize, globalSize)

  def code: String= kernel.code
}

sealed case class KernelNoSizes(kernel: Kernel) {
  //noinspection TypeAnnotation
  def as[F <: FunctionHelper](implicit ev: F#T <:< HList) = new {
    def apply(localSize: LocalSize, globalSize: GlobalSize): F#T => (F#R, TimeSpan[Time.ms]) =
      kernel.as[F](localSize, globalSize)

    def withSizes(localSize: LocalSize, globalSize: GlobalSize): F#T => (F#R, TimeSpan[Time.ms]) =
      kernel.as[F](localSize, globalSize)
  }

  def code: String= kernel.code
}
