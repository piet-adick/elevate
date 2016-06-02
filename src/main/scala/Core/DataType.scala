package Core

sealed trait DataType
sealed abstract class BasicType extends DataType

object bool extends BasicType { override def toString = "bool" }

object int extends BasicType { override def toString = "int" }

object int4 extends BasicType { override def toString = "int4" }

object float extends BasicType { override def toString = "float" }

case class ArrayType(size: Int, elemType: DataType) extends DataType

case class RecordType(fst: DataType, snd: DataType) extends DataType