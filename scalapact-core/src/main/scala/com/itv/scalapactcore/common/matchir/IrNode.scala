package com.itv.scalapactcore.common.matchir

import scala.language.{implicitConversions, postfixOps}

case class IrNode(label: String, value: Option[IrNodePrimitive], children: List[IrNode], ns: Option[String], attributes: IrNodeAttributes, isJsonArray: Boolean, path: IrNodePath) {

  import IrNodeEqualityResult._

  def =~(other: IrNode)(implicit rules: IrNodeMatchingRules): IrNodeEqualityResult = isEqualTo(other, strict = false, rules)
  def =<>=(other: IrNode)(implicit rules: IrNodeMatchingRules): IrNodeEqualityResult = isEqualTo(other, strict = true, rules)

  def isEqualTo(other: IrNode, strict: Boolean, rules: IrNodeMatchingRules): IrNodeEqualityResult = {
    val equality = check[Boolean](nodeType(path), this.isJsonArray, other.isJsonArray) +
    check[String](labelTest(path), this.label, other.label) +
    check[Option[IrNodePrimitive]](valueTest(strict)(path)(rules), this.value, other.value) +
    check[List[IrNode]](childrenTest(strict)(path)(rules)(this, other), this.children, other.children) +
    check[Option[String]](namespaceTest(path), this.ns, other.ns) +
    check[IrNodeAttributes](attributesTest(strict)(path)(rules), this.attributes, other.attributes) +
    check[IrNodePath](pathTest(strict)(path), this.path, other.path)

    RuleChecks.checkForNode(rules, path, this, other).getOrElse(equality)
  }

  val arrays: Map[String, List[IrNode]] =
    children.groupBy(_.label)

  val arraysKeys: List[String] = arrays.keys.toList

  def withNamespace(ns: String): IrNode = this.copy(ns = Option(ns))
  def withAttributes(attributes: IrNodeAttributes): IrNode = this.copy(attributes = attributes)
  def withPath(path: IrNodePath): IrNode = this.copy(path = path)
  def markAsJsonArray(boolean: Boolean): IrNode = this.copy(isJsonArray = boolean)

  def renderAsString: String = renderAsString(0)

  def renderAsString(indent: Int): String = {
    val i = List.fill(indent)("  ").mkString
    val n = ns.map("  namespace: " + _ + "").getOrElse("")
    val v = value.map(v => "  value: " + v.renderAsString).getOrElse("")
    val a = if(attributes.attributes.isEmpty) "" else s"  attributes: [${attributes.attributes.map(p => p._1 + "=" + p._2.value.renderAsString).mkString(", ")}]"
    val l = if(arraysKeys.isEmpty) "" else s"  arrays: [${arraysKeys.mkString(", ")}]"
    val c = if(children.isEmpty) "" else "\n" + children.map(_.renderAsString(indent + 1)).mkString("\n")
    val p = "  " + path.renderAsString
    s"$i- $label$n$v$a$l$p$c"
  }

}

object IrNodeEqualityResult {

  val nodeType: IrNodePath => (Boolean, Boolean) => IrNodeEqualityResult =
    path => (a, b) => {
      val f = (bb: Boolean) => if(bb) "array" else "object"

      if(a == b) IrNodesEqual
      else IrNodesNotEqual(s"Expected type '${f(a)}' but got '${f(b)}'", path)
    }

  val labelTest: IrNodePath => (String, String) => IrNodeEqualityResult =
    path => (a, b) => {
      if(a == b) IrNodesEqual else IrNodesNotEqual(s"Label '$a' did not match '$b'", path)
    }

  val valueTest: Boolean => IrNodePath => IrNodeMatchingRules => (Option[IrNodePrimitive], Option[IrNodePrimitive]) => IrNodeEqualityResult = {
    strict => path => rules => (a, b) =>
        val equality = if (strict) {
          (a, b) match {
            case (Some(v1: IrNodePrimitive), Some(v2: IrNodePrimitive)) =>
              if (v1 == v2) IrNodesEqual else IrNodesNotEqual(s"Value '${v1.renderAsString}' did not match '${v2.renderAsString}'", path)

            case (Some(v1: IrNodePrimitive), None) =>
              IrNodesNotEqual(s"Value '${v1.renderAsString}' did not match empty value", path)

            case (None, Some(v2: IrNodePrimitive)) =>
              IrNodesNotEqual(s"Empty value did not match '${v2.renderAsString}'", path)

            case (None, None) =>
              IrNodesEqual
          }
        } else {
          (a, b) match {
            case (Some(v1: IrNodePrimitive), Some(v2: IrNodePrimitive)) =>
              if (v1 == v2) IrNodesEqual else IrNodesNotEqual(s"Value '${v1.renderAsString}' did not match '${v2.renderAsString}'", path)

            case (Some(v1: IrNodePrimitive), None) =>
              IrNodesNotEqual(s"Value '${v1.renderAsString}' did not match empty value", path)

            case (None, Some(_: IrNodePrimitive)) =>
              IrNodesEqual

            case (None, None) =>
              IrNodesEqual
          }
        }

      RuleChecks.checkForPrimitive(rules, path, a, b, checkParentTypeRule = false).getOrElse(equality)
  }

  val namespaceTest: IrNodePath => (Option[String], Option[String]) => IrNodeEqualityResult = path => {
    case (Some(v1: String), Some(v2: String)) =>
      if(v1 == v2) IrNodesEqual else IrNodesNotEqual(s"Namespace '$v1' did not match '$v2'", path)

    case (Some(v1: String), None) =>
      IrNodesNotEqual(s"Namespace '$v1' did not match empty namespace", path)

    case (None, Some(v2: String)) =>
      IrNodesNotEqual(s"Empty namespace did not match '$v2'", path)

    case (None, None) =>
      IrNodesEqual
  }

  val pathTest: Boolean => IrNodePath => (IrNodePath, IrNodePath) => IrNodeEqualityResult =
    strict => path => (a, b) =>
      if(strict)
        if(a === b) IrNodesEqual else IrNodesNotEqual(s"Path '${a.renderAsString}' does not equal '${b.renderAsString}'", path)
      else
        if(a =~= b) IrNodesEqual else IrNodesNotEqual(s"Path '${a.renderAsString}' does not equal '${b.renderAsString}'", path)

  implicit private def listOfResultsToResult(l: List[IrNodeEqualityResult]): IrNodeEqualityResult =
    l match {
      case Nil => IrNodesEqual
      case x :: xs => xs.foldLeft(x)(_ + _)
    }

  private def checkChildren(strict: Boolean, rules: IrNodeMatchingRules, a: List[IrNode], b: List[IrNode]): IrNodeEqualityResult =
    a.zip(b).zipWithIndex.map { pi =>
      val p = pi._1
      val i = pi._2

      RuleChecks.checkForNode(rules, p._1.path, p._1, p._2)
        .orElse(RuleChecks.checkForNode(rules, p._1.path <~ i, p._1.withPath(p._1.path <~ i), p._2.withPath(p._1.path <~ i)))
        .getOrElse(p._1.isEqualTo(p._2, strict, rules))
    }

  val childrenTest: Boolean => IrNodePath => IrNodeMatchingRules => (IrNode, IrNode) => (List[IrNode], List[IrNode]) => IrNodeEqualityResult =
    strict => path => rules => (parentA, parentB) => (a, b) => {
      if (strict) {
        if (a.length != b.length) {
          val parentCheck: Option[IrNodeEqualityResult] = RuleChecks.checkForNode(rules, parentA.path, parentA, parentB)

          val childrenCheck: IrNodeEqualityResult = checkChildren(strict, rules, a, b)

          parentCheck.map(p => p + childrenCheck).getOrElse(IrNodesNotEqual(s"Differing number of children. Expected ${a.length} got ${b.length}", path))
        } else {
          checkChildren(strict, rules, a, b)
        }
      } else {
        a.map { n1 =>
          b.find { n2 =>
            RuleChecks.checkForNode(rules, n1.path, n1, n2).getOrElse(n1.isEqualTo(n2, strict, rules)).isEqual
          } match {
            case Some(_) => IrNodesEqual
            case None => IrNodesNotEqual(s"Could not find match for:\n${n1.renderAsString}", path)
          }
        }
      }
    }

  private val checkAttributesTest: IrNodePath => IrNodeMatchingRules => (IrNodeAttributes, IrNodeAttributes) => IrNodeEqualityResult = path => rules => (a, b) =>
    a.attributes.toList.map { p =>
      b.attributes.get(p._1) match {
        case None =>
          IrNodesNotEqual(s"Attribute ${p._1} was missing", path)

        case Some(v: IrNodeAttribute) =>
          if(v == p._2) IrNodesEqual
          else {
            RuleChecks.checkForPrimitive(
              rules,
              p._2.path,
              Option(p._2.value),
              Option(v.value),
              checkParentTypeRule = true
            ).getOrElse(IrNodesNotEqual(s"Attribute value for '${p._1}' of '${p._2.value.renderAsString}' does not equal '${v.value.renderAsString}'", path))
          }
      }
    }


  val attributesTest: Boolean => IrNodePath => IrNodeMatchingRules => (IrNodeAttributes, IrNodeAttributes) => IrNodeEqualityResult =
    strict => path => rules => (a, b) =>
      if(strict) {
        val as = a.attributes.toList
        val bs = b.attributes.toList
        val asNames = as.map(_._1)
        val bsNames = bs.map(_._1)

        if(asNames.length != bsNames.length) {
          IrNodesNotEqual(s"Differing number of attributes between ['${asNames.mkString(", ")}'] and ['${bsNames.mkString(", ")}']", path)
        } else if(asNames != bsNames) {
          IrNodesNotEqual(s"Differing attribute order between ['${asNames.mkString(", ")}'] and ['${bsNames.mkString(", ")}']", path)
        } else {
          checkAttributesTest(path)(rules)(a, b)
        }

      } else checkAttributesTest(path)(rules)(a, b)


  def check[A](f: (A, A) => IrNodeEqualityResult, propA: A, propB: A): IrNodeEqualityResult = f(propA, propB)

}

sealed trait IrNodeEqualityResult {

  val isEqual: Boolean

  def +(other: IrNodeEqualityResult): IrNodeEqualityResult =
    (this, other) match {
      case (IrNodesEqual, IrNodesEqual) => IrNodesEqual
      case (IrNodesEqual, r @ IrNodesNotEqual(_)) => r
      case (l @ IrNodesNotEqual(_), IrNodesEqual) => l
      case (IrNodesNotEqual(d1), IrNodesNotEqual(d2)) => IrNodesNotEqual(d1 ++ d2)
    }

  def renderAsString: String =
    this match {
      case IrNodesEqual =>
        "Nodes equal"

      case n: IrNodesNotEqual =>
        n.renderDifferences
    }

}
case object IrNodesEqual extends IrNodeEqualityResult {
  val isEqual: Boolean = true
}
case class IrNodesNotEqual(differences: List[IrNodeDiff]) extends IrNodeEqualityResult {
  val isEqual: Boolean = false

  def renderDifferences: String =
    differences.map(d => s"""Node at: ${d.path.renderAsString}\n  ${d.message}""").mkString("\n")
}

case class IrNodeDiff(message: String, path: IrNodePath)

object IrNodesNotEqual {
  def apply(message: String, path: IrNodePath): IrNodesNotEqual = IrNodesNotEqual(List(IrNodeDiff(message, path)))
}

object IrNode {

  def empty: IrNode =
    IrNode("", None, Nil, None, IrNodeAttributes.empty, isJsonArray = false, IrNodePathEmpty)

  def apply(label: String): IrNode =
    IrNode(label, None, Nil, None, IrNodeAttributes.empty, isJsonArray = false, IrNodePathEmpty)

  def apply(label: String, value: IrNodePrimitive): IrNode =
    IrNode(label, Option(value), Nil, None, IrNodeAttributes.empty, isJsonArray = false, IrNodePathEmpty)

  def apply(label: String, value: Option[IrNodePrimitive]): IrNode =
    IrNode(label, value, Nil, None, IrNodeAttributes.empty, isJsonArray = false, IrNodePathEmpty)

  def apply(label: String, children: IrNode*): IrNode =
    IrNode(label, None, children.toList, None, IrNodeAttributes.empty, isJsonArray = false, IrNodePathEmpty)

  def apply(label: String, children: List[IrNode]): IrNode =
    IrNode(label, None, children, None, IrNodeAttributes.empty, isJsonArray = false, IrNodePathEmpty)

}

sealed trait IrNodePrimitive {
  def isString: Boolean
  def isNumber: Boolean
  def isBoolean: Boolean
  def isNull: Boolean
  def asString: Option[String]
  def asNumber: Option[Double]
  def asBoolean: Option[Boolean]
  def renderAsString: String
  def primitiveTypeName: String
}
case class IrStringNode(value: String) extends IrNodePrimitive {
  def isString: Boolean = true
  def isNumber: Boolean = false
  def isBoolean: Boolean = false
  def isNull: Boolean = false
  def asString: Option[String] = Option(value)
  def asNumber: Option[Double] = None
  def asBoolean: Option[Boolean] = None
  def renderAsString: String = value
  def primitiveTypeName: String = "string"
}
case class IrNumberNode(value: Double) extends IrNodePrimitive {
  def isString: Boolean = false
  def isNumber: Boolean = true
  def isBoolean: Boolean = false
  def isNull: Boolean = false
  def asString: Option[String] = None
  def asNumber: Option[Double] = Option(value)
  def asBoolean: Option[Boolean] = None
  def renderAsString: String = value.toString
  def primitiveTypeName: String = "number"
}
case class IrBooleanNode(value: Boolean) extends IrNodePrimitive {
  def isString: Boolean = false
  def isNumber: Boolean = false
  def isBoolean: Boolean = true
  def isNull: Boolean = false
  def asString: Option[String] = None
  def asNumber: Option[Double] = None
  def asBoolean: Option[Boolean] = Option(value)
  def renderAsString: String = value.toString
  def primitiveTypeName: String = "boolean"
}
case object IrNullNode extends IrNodePrimitive {
  def isString: Boolean = false
  def isNumber: Boolean = false
  def isBoolean: Boolean = false
  def isNull: Boolean = true
  def asString: Option[String] = None
  def asNumber: Option[Double] = None
  def asBoolean: Option[Boolean] = None
  def renderAsString: String = "null"
  def primitiveTypeName: String = "null"
}

object IrNodeAttributes {

  def empty: IrNodeAttributes = IrNodeAttributes(Map.empty[String, IrNodeAttribute])

}

case class IrNodeAttributes(attributes: Map[String, IrNodeAttribute]) {

  def +(other: IrNodeAttributes): IrNodeAttributes =
    IrNodeAttributes(this.attributes ++ other.attributes)

}
case class IrNodeAttribute(value: IrNodePrimitive, path: IrNodePath)

object RuleChecks {

  implicit private def resultToOption(v: IrNodeEqualityResult): Option[IrNodeEqualityResult] =
    Option(v)

  implicit private def listResultsToOption(l: List[IrNodeEqualityResult]): Option[IrNodeEqualityResult] =
    l match {
      case Nil =>
        None

      case x :: xs =>
        xs.foldLeft(x)(_ + _)
    }

  def checkForNode(rules: IrNodeMatchingRules, path: IrNodePath, expected: IrNode, actual: IrNode): Option[IrNodeEqualityResult] =
    rules.validateNode(path, expected, actual)

  def checkForPrimitive(rules: IrNodeMatchingRules, path: IrNodePath, expected: Option[IrNodePrimitive], actual: Option[IrNodePrimitive], checkParentTypeRule: Boolean): Option[IrNodeEqualityResult] =
    (expected, actual) match {
      case (Some(e), Some(a)) =>
        rules.validatePrimitive(path, e, a, checkParentTypeRule)

      case (Some(e), None) =>
        IrNodesNotEqual(s"Missing 'actual' value '${e.renderAsString}'", path)

      case (None, Some(a)) =>
        IrNodesNotEqual(s"Missing 'expected' value '${a.renderAsString}'", path)

      case (None, None) =>
        IrNodesEqual
    }

}