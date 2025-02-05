package lightlens

import scala.quoted.*

extension [S, A](inline obj: S)

  /**
    * Create an object allowing modifying the given (deeply nested) field accessible in a `case class` hierarchy
    * via `path` on the given `obj`.
    *
    * All modifications are side-effect free and create copies of the original objects.
    *
    * You can use `.each` to traverse options, lists, etc.
    */
  inline def modify(inline path: S => A): PathModify[S, A] = ${modifyImpl('obj, 'path)}

case class PathModify[S, A](obj: S, f: (A => A) => S) {
  
  /**
    * Transform the value of the field(s) using the given function.
    *
    * @return A copy of the root object with the (deeply nested) field(s) modified.
    */
  def using(mod: A => A): S = f.apply(mod)

  /** An alias for [[using]]. Explicit calls to [[using]] are preferred over this alias, but quicklens provides
    * this option because code auto-formatters (like scalafmt) will generally not keep [[modify]]/[[using]]
    * pairs on the same line, leading to code like
    * {{{
    * x
    *   .modify(_.foo)
    *   .using(newFoo :: _)
    *   .modify(_.bar)
    *   .using(_ + newBar)
    * }}}
    * When using [[apply]], scalafmt will allow
    * {{{
    * x
    *   .modify(_.foo)(newFoo :: _)
    *   .modify(_.bar)(_ + newBar)
    * }}}
    * */
  def apply(mod: A => A): S = using(mod)

  /**
    * Transform the value of the field(s) using the given function, if the condition is true. Otherwise, returns the
    * original object unchanged.
    *
    * @return A copy of the root object with the (deeply nested) field(s) modified, if `condition` is true.
    */
  def usingIf(condition: Boolean)(mod: A => A): S = if condition then using(mod) else obj

  /**
    * Set the value of the field(s) to a new value.
    *
    * @return A copy of the root object with the (deeply nested) field(s) set to the new value.
    */
  def setTo(v: A): S = f.apply(Function.const(v))

  /**
      * Set the value of the field(s) to a new value, if it is defined. Otherwise, returns the original object
      * unchanged.
      *
      * @return A copy of the root object with the (deeply nested) field(s) set to the new value, if it is defined.
      */
  def setToIfDefined(v: Option[A]): S = v.fold(obj)(setTo)

  /**
    * Set the value of the field(s) to a new value, if the condition is true. Otherwise, returns the original object
    * unchanged.
    *
    * @return A copy of the root object with the (deeply nested) field(s) set to the new value, if `condition` is
    *         true.
    */
  def setToIf(condition: Boolean)(v: A): S = if condition then setTo(v) else obj
}

extension [F[_]: QuicklensFunctor, A](fa: F[A])
  def each: A = ???

private val shapeInfo = "focus must have shape: _.field1.each.field3"

def toPathModify[S: Type, A: Type](obj: Expr[S], f: Expr[(A => A) => S])(using Quotes): Expr[PathModify[S, A]] = '{ PathModify( ${obj}, ${f} ) }

def to[T: Type, R: Type](f: Expr[T] => Expr[R])(using Quotes): Expr[T => R] = '{ (x: T) => ${ f('x) } }

def modifyImpl[S, A](obj: Expr[S], focus: Expr[S => A])(using qctx: Quotes, tpeS: Type[S], tpeA: Type[A]): Expr[PathModify[S, A]] = {
  import qctx.reflect.*

  enum PathSymbol:
    case Field(name: String)
    case Each(givn: Term, typeTree: TypeTree)

  object PathSymbol {
    def specialSymbolByName(term: Term, methodName: String, typeTree: TypeTree): PathSymbol = methodName match {
      case "each" => Each(term, typeTree)
      case _ =>
        report.error(shapeInfo)
        ???
    }
  }

  def toPath(tree: Tree): Seq[PathSymbol] = {
    tree match {
      case Select(deep, ident) =>
        toPath(deep) :+ PathSymbol.Field(ident)
      case Apply(Apply(TypeApply(Ident(s), typeTrees), idents), List(ident: Ident)) =>
        idents.flatMap(toPath) :+ PathSymbol.specialSymbolByName(ident, s, typeTrees.last)
      case Apply(deep, idents) =>
        toPath(deep) ++ idents.flatMap(toPath)
      case i: Ident if i.name.startsWith("_") =>
        Seq.empty
      case _ =>
        report.error(shapeInfo)
        ???
    }
  }

  def termMethodByNameUnsafe(term: Term, name: String): Symbol = {
    term.tpe.typeSymbol.declaredMethod(name).head
  }

  def termAccessorMethodByNameUnsafe(term: Term, name: String): (Symbol, Int) = {
    val caseFields = term.tpe.typeSymbol.caseFields
    val idx = caseFields.map(_.name).indexOf(name)
    (caseFields.find(_.name == name).get, idx+1)
  }

  def mapToCopy[X](mod: Expr[A => A], objTerm: Term, path: Seq[PathSymbol]): Term = path match
    case Nil =>
      val apply = termMethodByNameUnsafe(mod.asTerm, "apply")
      Apply(Select(mod.asTerm, apply), List(objTerm))
    case (field: PathSymbol.Field) :: tail =>
      val copy = termMethodByNameUnsafe(objTerm, "copy")
      val (fieldMethod, idx) = termAccessorMethodByNameUnsafe(objTerm, field.name)
      val namedArg = NamedArg(field.name, mapToCopy(mod, Select(objTerm, fieldMethod), tail))
      val fieldsIdxs = 1.to(objTerm.tpe.typeSymbol.caseFields.length)
      val args = fieldsIdxs.map { i =>
        if(i == idx) namedArg
        else Select(objTerm, termMethodByNameUnsafe(objTerm, "copy$default$" + i.toString))
      }.toList
      Apply(
        Select(objTerm, copy),
        args
      )
    case (m: PathSymbol.Each) :: tail =>
      val defdefSymbol = Symbol.newMethod(
        Symbol.spliceOwner,
        "$anonfun",
        MethodType(List("x"))(_ => List(m.typeTree.tpe), _ => m.typeTree.tpe)
      )
      val mapMethod = termMethodByNameUnsafe(m.givn, "map")
      val map = TypeApply(
        Select(m.givn, mapMethod),
        List(m.typeTree, m.typeTree)
      )
      val defdefStatements = DefDef(
        defdefSymbol, {
          case List(List(x)) => Some(mapToCopy(mod, x.asExpr.asTerm, tail))
        }
      )
      val closure = Closure(Ref(defdefSymbol), None)
      val block = Block(List(defdefStatements), closure)
      Apply(map, List(objTerm, block))
  
  val focusTree: Tree = focus.asTerm
  val path = focusTree match {
    case Inlined(_, _, Block(List(DefDef(_, _, _, Some(p))), _)) =>
      toPath(p)
    case _ =>
      report.error(shapeInfo)
      ???
  }

  val objTree: Tree = obj.asTerm
  val objTerm: Term = objTree match {
    case Inlined(_, _, term) => term
  }
  
  val res: (Expr[A => A] => Expr[S]) = (mod: Expr[A => A]) => mapToCopy(mod, objTerm, path).asExpr.asInstanceOf[Expr[S]]
  toPathModify(obj, to(res))
}
