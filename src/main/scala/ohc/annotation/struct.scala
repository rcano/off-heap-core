package ohc
package annotation

import language.experimental.macros
import scala.annotation.{ StaticAnnotation, compileTimeOnly}
import scala.reflect.macros.whitebox.Context

@compileTimeOnly("structs should be processed by the compiler")
class struct(debug: Boolean = false) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro StructMacros.structMacro
}

class StructMacros(val c: Context) {
  import c.universe._


  def structMacro(annottees: Tree*): Tree = {
    val debug = c.prefix.tree match {
      case q"new struct(debug = ${Literal(Constant(debug: Boolean))})" => debug
      case _ => false
    }
    val res = annottees match {
      case Seq(clazz@ClassDef(_, nme, _, _)) =>
        val (c, o) = implementClassAndCompanion(clazz, q"object ${nme.toTermName}")
        q"{$c; $o}"

      case Seq(clazz@ClassDef(_, _, _, _), companion@ModuleDef(_, _, _)) =>
        val (c, o) = implementClassAndCompanion(clazz, companion)
        q"{$c; $o}"

      case other => c.abort(c.enclosingPosition, "Annotated element is not a class: " + other)
    }
    if(debug) c.info(c.enclosingPosition, showCode(res), true)
    res
  }
  private def implementClassAndCompanion(clazz: Tree, companion: Tree): (Tree, Tree) = {
    // check that the class is not a case class
    val classDef@ClassDef(_, _, _, _) = clazz
    if (classDef.mods.hasFlag(Flag.CASEACCESSOR)) c.abort(clazz.pos, "Cannot annotate a case class, because the generated by standard scala is not compatible with off-heap structures")

    val q"$mods class $tpname[..$tparams] $ctorMods(..$params) extends { ..$earlydefns } with ..$parents { $self => ..$stats }" = clazz

    //check that one of the tparams is an AllocatorDefinition
    if (tparams.size != 1) c.abort(c.enclosingPosition, "A structure must only define one type parameter such as T <: Allocator")
    val allocatorGenericParam = tparams.head match {
      case t@TypeDef(_, tparamName, _, TypeBoundsTree(lo, AppliedTypeTree(tpe, List(Ident(tparamName2))))) if {
          val typedTpe = c.typecheck(tpe, c.TYPEmode)

          tparamName == tparamName2 &&
          typedTpe.tpe.baseType(symbolOf[Allocator[_]]) != NoType
        } => tparamName
      case other => c.abort(other.pos, "You need to have one type parameter that extends Allocator")
    }

    //check that the body doesn't contain any val or var, this are not allowed
    stats foreach {
      case t@ValDef(mods, _, _, _) => c.error(t.pos, "Invalid variable declaration. Structures must declare their variables in the constructor")
      case _ =>
    }

    // fold the params obtaining getters and setters
    val (totalSize: Int, accessors: Seq[Tree]) = params.zipWithIndex.foldLeft((0, Seq[Tree]())) {
      case ((offset, accum), (t@ValDef(mods, name, tpe, rhs), fieldIndex)) =>
        val paramTpe = c.typecheck(tpe, c.TYPEmode, silent = true).tpe
        if (TypeSize.isDefinedAt(paramTpe)) {
          val typeSize = TypeSize(paramTpe)
          val position = offset
          val setterMods = if (mods.hasFlag(Flag.MUTABLE)) NoMods else Modifiers(NoFlags, tpname)
          val accessors = Seq(q"def $name(implicit a: $allocatorGenericParam) = a.memory.${TermName("get" + tpe)}(_ptr + $position)",
                              q"$setterMods def ${TermName(name + "_$eq")}(v: $tpe)(implicit a: $allocatorGenericParam) = a.memory.${TermName("set" + tpe)}(_ptr + $position, v)")
          (offset + typeSize, accum ++ accessors)
        } else {
          c.error(t.pos, "Unsized type " + tpe)
          (offset, accum)
        }
    }

    val resultClass = {
      val totalParents = Seq(tq"AnyVal", tq"_root_.ohc.Struct[$allocatorGenericParam]") ++ parents.filterNot { t =>
        val tpe = c.typecheck(t, c.TYPEmode, silent = true).tpe
        tpe =:= definitions.ObjectTpe || tpe =:= definitions.AnyRefTpe
      }
      q"""
        $mods class $tpname[$allocatorGenericParam <: _root_.ohc.Allocator[$allocatorGenericParam]](val _ptr: _root_.shapeless.tag.@@[Long, $allocatorGenericParam]) extends ..$totalParents {
          import scala.language.experimental.macros

          ..${accessors}

          ..$stats
        }
        """
    }

    val resultCompanion = {

      val q"$mods object $tpname extends { ..$earlydefns } with ..$parents { $self => ..$stats }" = companion

      val fieldAssignations = params map (p => q"""res.${TermName(p.name.decodedName.toString + "_$eq")}(${p.name})""")
      val applyBody = q"""
        val res = $tpname()
        ..$fieldAssignations
        res"""

      val applyMethod = q"""def apply[A <: _root_.ohc.Allocator[A]](..$params)(implicit alloc: A): ${tpname.toTypeName}[A] = { ..$applyBody }"""
      val paramNames = params.map(p => q"o.${p.name}")
      val unapplyMethod = q"""def unapply[A <: _root_.ohc.Allocator[A]](o: ${tpname.toTypeName}[A])(implicit a: A) = Some((..$paramNames))"""


      val newParents = tq"_root_.ohc.StructDef[${tpname.toTypeName}]" +: parents.filterNot { t =>
        val tpe = c.typecheck(t, c.TYPEmode, silent = true).tpe
        tpe =:= definitions.ObjectTpe || tpe =:= definitions.AnyRefTpe
      }

      val totalSizeTree = c.typecheck(Literal(Constant(totalSize)))

      q"""
      $mods object $tpname extends {..$earlydefns} with ..$newParents {
        ..$stats

        def apply[A <: _root_.ohc.Allocator[A]]()(implicit allocator: A): ${tpname.toTypeName}[A] = new ${tpname.toTypeName}[A](allocator allocate size)
        def apply[A <: _root_.ohc.Allocator[A]](ptr: _root_.shapeless.tag.@@[Long, A]): ${tpname.toTypeName}[A] = new ${tpname.toTypeName}[A](ptr)
        def size: ${totalSizeTree.tpe} = $totalSizeTree

        implicit val structDef = this

        $applyMethod
        $unapplyMethod
      }
      """
    }

    (resultClass, resultCompanion)
  }

  private val TypeSize: PartialFunction[Type, Int] = {
    case definitions.BooleanTpe => 1
    case definitions.CharTpe => 2
    case definitions.ByteTpe => 1
    case definitions.ShortTpe => 2
    case definitions.IntTpe => 4
    case definitions.LongTpe => 8
    case definitions.FloatTpe => 4
    case definitions.DoubleTpe => 8
  }
}
