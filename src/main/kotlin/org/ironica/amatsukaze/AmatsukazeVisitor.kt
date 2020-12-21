package org.ironica.amatsukaze

import amatsukazeGrammarVisitor
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.RuleNode
import org.antlr.v4.runtime.tree.TerminalNode
import kotlin.math.pow

sealed class DataType
object _SOME: DataType() // TODO just a placeholder for complicated array type, should be fixed
object _INT: DataType()
object _DOUBLE: DataType()
object _CHARACTER: DataType()
object _STRING: DataType()
object _BOOL: DataType()
object _VOID: DataType()
object _CALL: DataType()
object _FUNCTION: DataType()
object _STRUCT: DataType()
object _ENUM: DataType()
data class _ARRAY(val type: DataType): DataType()

private fun DataType.typeEquals(other: DataType): Boolean {
    return if (this !is _ARRAY) this == other
    else other is _ARRAY
}

sealed class Proto {
    abstract val prototype: Proto?
}
sealed class Literal(): Proto() {
    abstract val variability: Variability
}
data class IntegerL(override val variability: Variability, var content: Int, override val prototype: Prototype): Literal()
data class DoubleL(override val variability: Variability, var content: Double, override val prototype: Prototype): Literal()
data class BooleanL(override val variability: Variability, var content: Boolean, override val prototype: Prototype): Literal()
data class CharacterL(override val variability: Variability, var content: Char, override val prototype: Prototype): Literal()
data class StringL(override val variability: Variability, var content: String, override val prototype: Prototype): Literal()

data class ReturnedL(val content: Any): Throwable()
data class StructL(override val variability: Variability, var body: MutableMap<String, Literal>, override var prototype: Prototype): Literal()
data class FunctionL(override val variability: Variability, var head: FuncHead, var body: ParseTree, var closureScope: List<Scope>, override var prototype: Prototype): Literal()
data class ArrayL(override val variability: Variability, var subType: DataType, var content: MutableList<Literal> = mutableListOf(), override var prototype: Prototype): Literal()
data class Prototype (
    val members: MutableMap<String, Literal>,
    override var prototype: Proto? = null,
    var ctor: Literal? = null
): Proto()

data class FuncHead(val name: String, val params: List<String>, val types: List<DataType>, val refs: List<Boolean>, val ret: DataType) {

    fun pseudoEquals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FuncHead

        if (name != other.name) return false
        if (refs != other.refs) return false
        if (types != other.types) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + types.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FuncHead

        if (name != other.name) return false
        if (params != other.params) return false
        if (types != other.types) return false
        if (refs != other.refs) return false
        if (ret != other.ret) return false

        return true
    }
}

typealias Scope = MutableMap<String, Literal>

class AmatsukazeVisitor(private val manager: PlaygroundManager): amatsukazeGrammarVisitor<Any> {

    private var _break = false
    private var _continue = false

    private val typeTable = mutableMapOf(
        "Integer" to Prototype(mutableMapOf()), "Double" to Prototype(mutableMapOf()), "Bool" to Prototype(mutableMapOf()),
        "Character" to Prototype(mutableMapOf()), "String" to Prototype(mutableMapOf()), "Struct" to Prototype(mutableMapOf()),
        "Function" to Prototype(mutableMapOf()), "Array" to Prototype(mutableMapOf())
    )

    private var inFunc = mutableListOf<Boolean>()
    private var currentFunc = 0
    private var externalDepth = 0
    private var internalDepth = 0
    private val funcEntriesDepth = mutableListOf<Int>()

    private val functionTable = mutableMapOf<FuncHead, Pair<ParseTree, Int>>()

    private val anonymousFuncIndices = mutableListOf(0)

    private fun assignAnonymousFuncIndex(level: Int): Int {
        return anonymousFuncIndices[level]++
    }

    /*
        default: print, typeof, isSame(a, b)
        Object -> toString
        String -> size, replace, subStr, toCharArray
        Boolean -> not, and, or, xor
        Character -> isDigit, isAlpha, isUpper, isLower, isEmpty, toInt
        Struct -> varDump, count, erase, clear
        Array -> size, clear, swap, first, last, pushBack, pushFront, popBack, popFront
              -> (hof) foreach, map, filter, all, any (not supported)
        Some functions can be done by adding extensions on prototype chain.
         */

    private val variableTable = mutableMapOf<String, Literal>()
    private val variableDepth = mutableMapOf<String, Int>()
    private val internalVariables = mutableListOf<Scope>()
    private val internalVariableDepth = mutableMapOf<String, Int>()

    private fun queryVariableTable(of: String): Pair<Literal?, Int> {
        for (i in internalVariables.indices.reversed()) {
            if (internalVariables[i].containsKey(of)) {
                return internalVariables[i][of] to i
            }
        }
        if (variableTable.containsKey(of)) {
            return variableTable[of] to -1
        }
        return null to -2
    }

    private fun assignVariable(left: Literal, right: Any): Literal {
        when (left) {
            is IntegerL -> {
                if (left.variability == Variability.CST) throw Exception("Attempt modify constant")
                when (right) {
                    is Int -> left.content = right
                    is IntegerL -> left.content = right.content
                    else -> throw Exception("Type of lhs and rhs of assignment don't match")
                }
            }
            is DoubleL -> {
                if (left.variability == Variability.CST) throw Exception("Attempt modify constant")
                when (right) {
                    is Double -> left.content = right
                    is DoubleL -> left.content = right.content
                    else -> throw Exception("Type of lhs and rhs of assignment don't match")
                }
            }
            is BooleanL -> {
                if (left.variability == Variability.CST) throw Exception("Attempt modify constant")
                when (right) {
                    is Boolean -> left.content = right
                    is BooleanL -> left.content = right.content
                    else -> throw Exception("Type of lhs and rhs of assignment don't match")
                }
            }
            is CharacterL -> {
                if (left.variability == Variability.CST) throw Exception("Attempt modify constant")
                when (right) {
                    is Pair<*, *>  -> {
                        if (right.second is _CHARACTER)
                            left.content = right.first as Char
                    }
                    is CharacterL -> left.content = right.content
                    else -> throw Exception("Type of lhs and rhs of assignment don't match")
                }
            }
            is StringL -> {
                if (left.variability == Variability.CST) throw Exception("Attempt modify constant")
                when (right) {
                    is Pair<*, *>  -> {
                        if (right.second is _STRING)
                            left.content = right.first as String
                    }
                    is StringL -> left.content = right.content
                    else -> throw Exception("Type of lhs and rhs of assignment don't match")
                }
            }
            is StructL -> {
                if (left.variability == Variability.CST) throw Exception("Attempt modify constant")
                when (right) {
                    is StructL -> left.body = right.body
                    else -> throw Exception("Type of lhs and rhs of assignment don't match")
                }
            }
            is FunctionL -> {
                if (left.variability == Variability.CST) throw Exception("Attempt modify constant")
                when (right) {
                    is FunctionL -> {
                        left.head = right.head
                        left.body = right.body
                        left.closureScope = right.closureScope
                    }
                    else -> throw Exception("Type of lhs and rhs of assignment don't match")
                }
            }
            is ArrayL -> {
                if (left.variability == Variability.CST) throw Exception("Attempt modify constant")
                when (right) {
                    is ArrayL -> {
                        if (left.subType.typeEquals(right.subType)) {
                            left.content = right.content
                        }
                        else throw Exception("Array type mismatch on lhs and rhs")
                    }
                    else -> throw Exception("Type of lhs and rhs of assignment don't match")
                }
            }
        }
        return left
    }

    private fun checkTypeEquality(right: Literal, type: DataType): DataType? {
        return if (right is IntegerL && type is _INT
                || right is DoubleL && type is _DOUBLE
                || right is BooleanL && type is _BOOL
                || right is CharacterL && type is _CHARACTER
                || right is StringL && type is _STRING
                || right is FunctionL && type is _FUNCTION
                || right is StructL && type is _STRUCT
                || right is ArrayL && type is _ARRAY) type else null
    }

    private fun checkTypeOfLiteralsIdentical(left: Literal, right: Literal): Boolean {
        return left is IntegerL && right is IntegerL
                || left is DoubleL && right is DoubleL
                || left is BooleanL && right is BooleanL
                || left is CharacterL && right is CharacterL
                || left is StringL && right is StringL
                || left is FunctionL && right is FunctionL
                || left is StructL && right is StructL
                || left is ArrayL && right is ArrayL
    }

    private fun checkArrayType(type: DataType, right: ArrayL) {
        when (type) {
            is _INT -> if (right.content.none { it !is IntegerL }) return
            is _DOUBLE -> if (right.content.none { it !is DoubleL }) return
            is _BOOL -> if (right.content.none { it !is BooleanL }) return
            is _CHARACTER -> if (right.content.none { it !is CharacterL }) return
            is _STRING -> if (right.content.none { it !is StringL }) return
            is _STRUCT -> if (right.content.none { it !is StructL }) return
            is _ARRAY -> if (right.content.none { it !is ArrayL }) return
            is _FUNCTION -> if (right.content.none { it !is FunctionL }) return
        }
        throw Exception("Array type check failed")
    }

    private fun inferArrayType(right: ArrayL): DataType {
        if (right.content.all { it is IntegerL }) return _INT
        if (right.content.all { it is DoubleL }) return _DOUBLE
        if (right.content.all { it is BooleanL }) return _BOOL
        if (right.content.all { it is CharacterL }) return _CHARACTER
        if (right.content.all { it is StringL }) return _STRING
        if (right.content.all { it is StructL }) return _STRUCT
        if (right.content.all { it is FunctionL }) return _FUNCTION
        if (right.content.all { it is ArrayL }) return _ARRAY(_SOME)
        throw Exception("Array type inference failed")
    }

    private fun inferPrimaryToArrayType(right: Array<Literal>): DataType {
        if (right.all { it is IntegerL }) return _INT
        if (right.all { it is DoubleL }) return _DOUBLE
        if (right.all { it is BooleanL }) return _BOOL
        if (right.all { it is CharacterL }) return _CHARACTER
        if (right.all { it is StringL }) return _STRING
        if (right.all { it is StructL }) return _STRUCT
        if (right.all { it is FunctionL }) return _FUNCTION
        if (right.all { it is ArrayL }) return _ARRAY(_SOME)
        throw Exception("Array type inference failed")
    }

    private fun declareConstantOrVariable(type: DataType?, right: Literal, constant: Boolean): Literal? {
        if (type != null) {
            val checkedType = checkTypeEquality(type = type, right = right) ?: throw Exception("Declaration type check failed")
            return when (checkedType) {
                _INT -> {
                    if (constant) IntegerL(Variability.CST, (right as IntegerL).content, right.prototype)
                    else IntegerL(Variability.VAR, (right as IntegerL).content, right.prototype)
                }
                _DOUBLE -> {
                    if (constant) DoubleL(Variability.CST, (right as DoubleL).content, right.prototype)
                    else DoubleL(Variability.VAR, (right as DoubleL).content, right.prototype)
                }
                _BOOL -> {
                    if (constant) BooleanL(Variability.CST, (right as BooleanL).content, right.prototype)
                    else BooleanL(Variability.VAR, (right as BooleanL).content, right.prototype)
                }
                _CHARACTER -> {
                    if (constant) CharacterL(Variability.CST, (right as CharacterL).content, right.prototype)
                    else CharacterL(Variability.VAR, (right as CharacterL).content, right.prototype)
                }
                _STRING -> {
                    if (constant) StringL(Variability.CST, (right as StringL).content, right.prototype)
                    else StringL(Variability.VAR, (right as StringL).content, right.prototype)
                }
                _STRUCT -> {
                    if (constant) StructL(Variability.CST, (right as StructL).body, right.prototype)
                    else StructL(Variability.VAR, (right as StructL).body, right.prototype)
                }
                _FUNCTION -> {
                    if (constant) FunctionL(Variability.CST, (right as FunctionL).head, right.body, right.closureScope, right.prototype)
                    else FunctionL(Variability.VAR, (right as FunctionL).head, right.body, right.closureScope, right.prototype)
                }
                is _ARRAY -> {
                    checkArrayType(checkedType.type, (right as ArrayL))
                    if (constant) ArrayL(Variability.CST, checkedType.type, (right as ArrayL).content, right.prototype)
                    else ArrayL(Variability.VAR, checkedType.type, (right as ArrayL).content, right.prototype)
                }
                else -> throw Exception("This could not happen")
            }
        } else {
            return when (right) {
                is IntegerL -> {
                    if (constant) IntegerL(Variability.CST, right.content, right.prototype)
                    else IntegerL(Variability.VAR, right.content, right.prototype)
                }
                is DoubleL -> {
                    if (constant) DoubleL(Variability.CST, right.content, right.prototype)
                    else DoubleL(Variability.VAR, right.content, right.prototype)
                }
                is BooleanL -> {
                    if (constant) BooleanL(Variability.CST, right.content, right.prototype)
                    else BooleanL(Variability.VAR, right.content, right.prototype)
                }
                is CharacterL -> {
                    if (constant) CharacterL(Variability.CST, right.content, right.prototype)
                    else CharacterL(Variability.VAR, right.content, right.prototype)
                }
                is StringL -> {
                    if (constant) StringL(Variability.CST, right.content, right.prototype)
                    else StringL(Variability.VAR, right.content, right.prototype)
                }
                is StructL -> {
                    if (constant) StructL(Variability.CST, right.body, right.prototype)
                    else StructL(Variability.VAR, right.body, right.prototype)
                }
                is FunctionL -> {
                    if (constant) FunctionL(Variability.CST, right.head, right.body, right.closureScope, right.prototype)
                    else FunctionL(Variability.VAR, right.head, right.body, right.closureScope, right.prototype)
                }
                is ArrayL -> {
                    val inferredType = inferArrayType(right)
                    if (constant) ArrayL(Variability.CST, inferredType, right.content, right.prototype)
                    else ArrayL(Variability.VAR, inferredType, right.content, right.prototype)
                }
            }
        }
    }

    override fun visit(tree: ParseTree?): Any {
        return tree?.accept(this) ?: Exception("Encountered error while visiting AST")
//        try {
//            return tree?.accept(this)!!
//        } catch (e: Exception) {
//            throw Exception("Encountered error while visiting AST:\n    $e")
//        }
    }

    override fun visitChildren(node: RuleNode?): Any {
        TODO("Not yet implemented")
    }

    override fun visitTerminal(node: TerminalNode?): Any {
        if (node?.text != null) return node.text
        throw Exception("Encountered error while visiting terminal node")
    }

    override fun visitErrorNode(node: ErrorNode?): Any {
        throw Exception("Return from ErrorNode")
    }

    override fun visitTop_level(ctx: amatsukazeGrammarParser.Top_levelContext?): Any {
        return visit(ctx?.statements())
    }

    override fun visitLiteral(ctx: amatsukazeGrammarParser.LiteralContext?): Any {
        return visit(ctx?.children?.get(0))
    }

    override fun visitNumeric_literal(ctx: amatsukazeGrammarParser.Numeric_literalContext?): Any {
        return when {
            ctx?.childCount == 1 -> visit(ctx.getChild(0))
            ctx?.integer_literal()?.isEmpty!! -> - (visit(ctx.getChild(1)) as Double)
            else -> - (visit(ctx.getChild(1)) as Int)
        }
    }

    override fun visitBoolean_literal(ctx: amatsukazeGrammarParser.Boolean_literalContext?): Any {
        return when (ctx?.text) {
            "true" -> true
            "false" -> false
            else -> throw Exception("Cannot parse literal to boolean type")
        }
    }

    override fun visitChar_sequence_literal(ctx: amatsukazeGrammarParser.Char_sequence_literalContext?): Any {
        return if (ctx?.STRING_LITERAL() == null)
            ctx?.getChild(0)?.text!![0] to _CHARACTER
        else
            ctx.getChild(0)?.text!! to _STRING
    }

    override fun visitNil_literal(ctx: amatsukazeGrammarParser.Nil_literalContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitInteger_literal(ctx: amatsukazeGrammarParser.Integer_literalContext?): Any {
        return "${visit(ctx?.DECIMAL_LITERAL())}".toInt()
    }

    override fun visitDouble_literal(ctx: amatsukazeGrammarParser.Double_literalContext?): Any {
        return "${visit(ctx?.DECIMAL_LITERAL(0))}.${visit(ctx?.DECIMAL_LITERAL(1))}".toDouble()
    }

    override fun visitAssignmentExpr(ctx: amatsukazeGrammarParser.AssignmentExprContext?): Any {
        return visit(ctx?.assignment_expression())
    }

    // TODO need to rewrite this
    override fun visitBoolComparativeExpr(ctx: amatsukazeGrammarParser.BoolComparativeExprContext?): Any {
        val left = visit(ctx?.expression(0))
        val right = visit(ctx?.expression(1))
        if (left is BooleanL && right is BooleanL) {
            left.content = if (ctx?.op?.type == amatsukazeGrammarParser.EQ) left.content == right.content else left.content != right.content
            return left
        }
        if ((left is IntegerL || left is DoubleL) && right is BooleanL) {
            when (left) {
                is IntegerL -> right.content = if (ctx?.op?.type == amatsukazeGrammarParser.EQ) (left.content != 0) == right.content else (left.content != 0) != right
                is DoubleL -> right.content = if (ctx?.op?.type == amatsukazeGrammarParser.EQ) (left.content != 0.0) == right.content else (left.content != 0.0) != right
            }
            return right // bad idea to modify right or left
        }
        if (left is BooleanL && (right is IntegerL || right is DoubleL)) {
            when (right) {
                is IntegerL -> left.content = if (ctx?.op?.type == amatsukazeGrammarParser.EQ) (right.content != 0) == left.content else (right.content != 0) != left
                is DoubleL -> left.content = if (ctx?.op?.type == amatsukazeGrammarParser.EQ) (right.content != 0.0) == left.content else (right.content != 0.0) != left
            }
            return left
        }
        if ((left is IntegerL || left is DoubleL) && (right is IntegerL || right is DoubleL)) {
            when (left) {
                is IntegerL -> when (right) {
                    is IntegerL -> return BooleanL(Variability.CST, if (ctx?.op?.type == amatsukazeGrammarParser.EQ) (left.content != 0) == (right.content != 0) else (left.content != 0) != (right.content != 0), typeTable["Bool"]!!)
                    is DoubleL -> return BooleanL(Variability.CST, if (ctx?.op?.type == amatsukazeGrammarParser.EQ) (left.content != 0) == (right.content != 0.0) else (left.content != 0) != (right.content != 0.0), typeTable["Bool"]!!)
                }
                is DoubleL -> when (right) {
                    is IntegerL -> return BooleanL(Variability.CST, if (ctx?.op?.type == amatsukazeGrammarParser.EQ) (left.content != 0.0) == (right.content != 0) else (left.content != 0.0) != (right.content != 0), typeTable["Bool"]!!)
                    is DoubleL -> return BooleanL(Variability.CST, if (ctx?.op?.type == amatsukazeGrammarParser.EQ) (left.content != 0.0) == (right.content != 0.0) else (left.content != 0.0) != (right.content != 0.0), typeTable["Bool"]!!)
                }
            } // Looks like a mess
        }
        TODO("Equality test for function, struct and array")
        throw Exception("Boolean comparative expression on unsupported type")
    }

    override fun visitStruct_call(ctx: amatsukazeGrammarParser.Struct_callContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitAriAssignmentExpr(ctx: amatsukazeGrammarParser.AriAssignmentExprContext?): Any {
        val left = visit(ctx?.pattern()) as String
        if (!(left[0].isLetter() || left[0] == '_')) throw Exception("Illegal variable name")
        var right = visit(ctx?.expression())
        if (right is BooleanL) {
            val newright = if (right.content) IntegerL(Variability.CST, 1, typeTable["Integer"]!!) else IntegerL(Variability.CST, 0, typeTable["Integer"]!!)
            right = newright
        }
        val lvar = queryVariableTable(left)
        if (lvar.first == null) throw Exception("Variable not defined!")
        if (!checkTypeOfLiteralsIdentical(lvar.first as Literal, right as Literal)) throw Exception("Type mismatch")
        val new: Literal = when (val old = lvar.first) {
            is IntegerL -> when (ctx?.op?.type) {
                amatsukazeGrammarParser.ADDEQ -> IntegerL(Variability.CST, old.content + (right as IntegerL).content, typeTable["Integer"]!!)
                amatsukazeGrammarParser.SUBEQ -> IntegerL(Variability.CST, old.content - (right as IntegerL).content, typeTable["Integer"]!!)
                amatsukazeGrammarParser.MULEQ -> IntegerL(Variability.CST, old.content * (right as IntegerL).content, typeTable["Integer"]!!)
                amatsukazeGrammarParser.DIVEQ -> IntegerL(Variability.CST, old.content / (right as IntegerL).content, typeTable["Integer"]!!)
                else -> IntegerL(Variability.CST, old.content % (right as IntegerL).content, typeTable["Integer"]!!)
            }
            is DoubleL -> when (ctx?.op?.type) {
                amatsukazeGrammarParser.ADDEQ -> DoubleL(Variability.CST, old.content + (right as DoubleL).content, typeTable["Double"]!!)
                amatsukazeGrammarParser.SUBEQ -> DoubleL(Variability.CST, old.content - (right as DoubleL).content, typeTable["Double"]!!)
                amatsukazeGrammarParser.MULEQ -> DoubleL(Variability.CST, old.content * (right as DoubleL).content, typeTable["Double"]!!)
                amatsukazeGrammarParser.DIVEQ -> DoubleL(Variability.CST, old.content / (right as DoubleL).content, typeTable["Double"]!!)
                else -> DoubleL(Variability.CST, old.content % (right as IntegerL).content, typeTable["Double"]!!)
            }
            is StringL -> {
                if (ctx?.op?.type == amatsukazeGrammarParser.ADDEQ)
                    StringL(Variability.CST, old.content + (right as StringL).content, typeTable["String"]!!)
                else throw Exception("Arithmetic assignment: unsupported operation")
            }
            else -> throw Exception("Arithmetic assignment: unsupported type")
        }
        return assignVariable(lvar.first!!, new)
    }

    override fun visitExponentExpr(ctx: amatsukazeGrammarParser.ExponentExprContext?): Any {
        val left = visit(ctx?.expression(0))
        val right = visit(ctx?.expression(1))
        assert((left is IntegerL || left is DoubleL) && (right is DoubleL || right is IntegerL))
        var leftctn = 0.0
        var rightctn = 0.0
        if (left is IntegerL) leftctn = left.content.toDouble()
        if (left is DoubleL) leftctn = left.content
        if (right is IntegerL) rightctn = right.content.toDouble()
        if (right is DoubleL) rightctn = right.content
        return DoubleL(Variability.CST, leftctn.pow(rightctn), typeTable["Double"]!!)
    }

    override fun visitAddSubExpr(ctx: amatsukazeGrammarParser.AddSubExprContext?): Any {
        val left = visit(ctx?.expression(0)) as Literal
        val right = visit(ctx?.expression(1)) as Literal
        if ((left is BooleanL || left is DoubleL || left is IntegerL) && (right is BooleanL || right is DoubleL || right is IntegerL)) {
            var lcontentInt = 0
            var lcontentDbl = 0.0
            var rcontentInt = 0
            var rcontentDbl = 0.0
            var dblFlag = false
            if (left is BooleanL)
                lcontentInt = if (left.content) 1 else 0
            if (right is BooleanL)
                rcontentInt = if (right.content) 1 else 0
            if (left is IntegerL)
                lcontentInt = left.content
            if (right is IntegerL)
                rcontentInt = right.content
            if (left is DoubleL) {
                lcontentDbl = left.content
                dblFlag = true
            }
            if (right is DoubleL) {
                rcontentDbl = right.content
                dblFlag = true
            }
            if (dblFlag) {
                val ans = when (ctx?.op?.type) {
                    amatsukazeGrammarParser.ADD -> lcontentDbl + rcontentDbl + lcontentInt + rcontentInt
                    else -> lcontentDbl + lcontentInt  - rcontentDbl - rcontentInt
                }
                return DoubleL(Variability.CST, ans, typeTable["Double"]!!)
            } else {
                val ans = when (ctx?.op?.type) {
                    amatsukazeGrammarParser.ADD -> lcontentInt + rcontentInt
                    else -> lcontentInt  - rcontentInt
                }
                return IntegerL(Variability.CST, ans, typeTable["Integer"]!!)
            }
        }
        if ((left is StringL || left is CharacterL) && (right is StringL || right is CharacterL)) {
            if (ctx?.op?.type == amatsukazeGrammarParser.ADD) {
                var leftctn = ""
                var rightctn = ""
                if (left is StringL) leftctn = left.content
                if (left is CharacterL) leftctn = left.content.toString()
                if (right is StringL) rightctn = right.content
                if (right is CharacterL) rightctn = right.content.toString()
                return StringL(Variability.CST, leftctn + rightctn, typeTable["String"]!!)
            }
        }
        throw Exception("AddSub: on unsupported type")
    }

    private fun handleFunctionCall(
        func: amatsukazeGrammarParser.Function_call_expressionContext,
        vari: amatsukazeGrammarParser.Variable_expressionContext? = null
    ): Any {
        if (vari == null) {

        } else {

        }
        TODO("Delegated Function Call Handler, not yet implemented")
    }

    override fun visitFunction_call(ctx: amatsukazeGrammarParser.Function_callContext?): Any {
        return handleFunctionCall(ctx?.function_call_expression()!!)
    }

    override fun visitMemberExpr(ctx: amatsukazeGrammarParser.MemberExprContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitAriComparativeExpr(ctx: amatsukazeGrammarParser.AriComparativeExprContext?): Any {
        val left = visit(ctx?.expression(0))
        val right = visit(ctx?.expression(1))
        if ((left is IntegerL || left is DoubleL) && (right is IntegerL || right is DoubleL)) {
            var lvalue = 0.0
            var rvalue = 0.0
            if (left is IntegerL) lvalue = left.content.toDouble()
            if (left is DoubleL) lvalue = left.content
            if (right is IntegerL) rvalue = right.content.toDouble()
            if (right is DoubleL) rvalue = right.content
            val ret = when (ctx?.op?.type) {
                amatsukazeGrammarParser.GT -> lvalue > rvalue
                amatsukazeGrammarParser.LT -> lvalue < rvalue
                amatsukazeGrammarParser.GEQ -> lvalue >= rvalue
                else -> lvalue <= rvalue
            }
            return BooleanL(Variability.CST, ret, typeTable["Bool"]!!)
        }
        throw Exception("Arithmetic comparable expression on unsupported type")
    }

    override fun visitLiteralValueExpr(ctx: amatsukazeGrammarParser.LiteralValueExprContext?): Any {
        return visit(ctx?.literal_expression())
    }

    override fun visitMulDivModExpr(ctx: amatsukazeGrammarParser.MulDivModExprContext?): Any {
        val left = visit(ctx?.expression(0)) as Literal
        val right = visit(ctx?.expression(1)) as Literal
        if ((left is BooleanL || left is DoubleL || left is IntegerL) && (right is BooleanL || right is DoubleL || right is IntegerL)) {
            var lcontentInt = 1
            var lcontentDbl = 1.0
            var rcontentInt = 1
            var rcontentDbl = 1.0
            var dblFlag = false
            if (left is BooleanL)
                lcontentInt = if (left.content) 1 else 0
            if (right is BooleanL)
                rcontentInt = if (right.content) 1 else 0
            if (left is IntegerL)
                lcontentInt = left.content
            if (right is IntegerL)
                rcontentInt = right.content
            if (left is DoubleL) {
                lcontentDbl = left.content
                dblFlag = true
            }
            if (right is DoubleL) {
                rcontentDbl = right.content
                dblFlag = true
            }
            if (dblFlag) {
                val ans = when (ctx?.op?.type) {
                    amatsukazeGrammarParser.MUL -> lcontentDbl * lcontentInt * rcontentDbl * rcontentInt
                    amatsukazeGrammarParser.DIV -> lcontentDbl * lcontentInt / rcontentDbl / rcontentInt
                    else -> (lcontentDbl * lcontentInt) % (rcontentDbl * rcontentInt)
                }
                return DoubleL(Variability.CST, ans, typeTable["Double"]!!)
            } else {
                val ans = when (ctx?.op?.type) {
                    amatsukazeGrammarParser.MUL -> lcontentInt * rcontentInt
                    amatsukazeGrammarParser.DIV -> lcontentInt / rcontentInt
                    else -> lcontentInt % rcontentInt
                }
                return IntegerL(Variability.CST, ans, typeTable["Double"]!!)
            }
        }
        throw Exception("MulDivMod: on unsupported type")
    }

    override fun visitVariableExpr(ctx: amatsukazeGrammarParser.VariableExprContext?): Any {
        return visit(ctx?.variable_expression())
    }

    override fun visitIsNestedCondition(ctx: amatsukazeGrammarParser.IsNestedConditionContext?): Any {
        val left = visit(ctx?.expression(0)) as Literal
        val right = visit(ctx?.expression(1)) as Literal
        assert((left is BooleanL || left is IntegerL || left is DoubleL) && (right is BooleanL || right is IntegerL || right is BooleanL))
        var leftctn = false
        var rightctn = false
        if (left is BooleanL) leftctn = left.content
        if (right is BooleanL) rightctn = right.content
        if (left is IntegerL) leftctn = left.content != 0
        if (right is IntegerL) rightctn = right.content != 0
        if (left is DoubleL) leftctn = left.content != 0.0
        if (right is DoubleL) rightctn = right.content != 0.0
        return BooleanL(Variability.CST, if (ctx?.op?.type == amatsukazeGrammarParser.AND) leftctn && rightctn else leftctn || rightctn, typeTable["Bool"]!!)
    }

    override fun visitExprFuncDeclExpr(ctx: amatsukazeGrammarParser.ExprFuncDeclExprContext?): Any {
        return visit(ctx?.expressional_function_declaration())
    }

    override fun visitIsNegativeCondition(ctx: amatsukazeGrammarParser.IsNegativeConditionContext?): Any {
        return !(visit(ctx?.getChild(1)) as Boolean)
    }

    override fun visitRangeExpression(ctx: amatsukazeGrammarParser.RangeExpressionContext?): Any {
        try {
            val lowerLiteral = visit(ctx?.expression(0))
            val upperLiteral = visit(ctx?.expression(1))
            var upper: Int
            var lower: Int
            upper = if (upperLiteral is IntegerL) {
                upperLiteral.content
            } else {
                (visit(ctx?.expression(1)) as IntegerL).content
            }
            lower = if (lowerLiteral is IntegerL) {
                lowerLiteral.content
            } else {
                (visit(ctx?.expression(0)) as IntegerL).content
            }
            if (ctx?.op?.type == amatsukazeGrammarParser.UNTIL) {
                if (ctx?.STEP() == null) {
                    return (lower until upper)
                }
                val steps = (visit(ctx.expression(2)) as IntegerL).content
                return (lower until upper step steps)
            }
            if (ctx?.op?.type == amatsukazeGrammarParser.THROUGH) {
                if (ctx?.STEP() == null) {
                    return (lower..upper)
                }
                val steps = (visit(ctx.expression(2)) as IntegerL).content
                return (lower .. upper step steps)
            }
            if (ctx?.op?.type == amatsukazeGrammarParser.DUNTIL) {
                if (ctx?.STEP() == null) {
                    return (lower downTo upper + 1)
                }
                val steps = (visit(ctx.expression(2)) as IntegerL).content
                return (lower downTo upper + 1 step steps)
            }
            if (ctx?.op?.type == amatsukazeGrammarParser.DTHROUGH) {
                if (ctx?.STEP() == null) {
                    return (lower downTo upper)
                }
                val steps = (visit(ctx.expression(2)) as IntegerL).content
                return (lower downTo upper step steps)
            }
            throw Exception("Range expression on unsupported values")
        } catch (e: Exception) {
            throw Exception("Something goes wrong within range expression:\n    ${e.message}")
        }
    }

    override fun visitSubscriExpr(ctx: amatsukazeGrammarParser.SubscriExprContext?): Any {
        return visit(ctx?.subscript_expression())
    }

    override fun visitParenthesisExpr(ctx: amatsukazeGrammarParser.ParenthesisExprContext?): Any {
        return visit(ctx?.expression())
    }

    // TODO review logic of assignment regarding struct
    override fun visitAssignment_expression(ctx: amatsukazeGrammarParser.Assignment_expressionContext?): Any {
        val left = visit(ctx?.pattern())
        if (left is String) {
            if (!(left[0].isLetter() || left[0] == '_')) throw Exception("Illegal variable name")
            val right = visit(ctx?.expression())
            // println("right: $right")
            val lvar = queryVariableTable(left)
            if (lvar.first == null) throw Exception("Variable not defined!")
            return assignVariable(lvar.first!!, right)
        } else { // (Literal) or (Literal) -> attribute's name
            when (left) {
                is Literal -> {
                    val right = visit(ctx?.expression())
                    if (!checkTypeOfLiteralsIdentical(left, right as Literal)) throw Exception("Type mismatch")
                    return assignVariable(left, right)
                }
                is Pair<*, *> -> {
                    val right = visit(ctx?.expression())
                    val variable = left.first
                    when (variable) {
                        is StructL -> {
                            variable.body[left.second as String] = right as Literal
                            return variable.body[left.second as String]!!
                        }
                        is Prototype -> {
                            variable.members[left.second as String] = right as Literal
                            return variable.members[left.second as String]!!
                        }
                    }
                }
                else -> throw Exception("This could not happen")
            }
        }
        throw Exception("Something goes wrong while visiting assignment expression")
    }

    override fun visitLiteral_expression(ctx: amatsukazeGrammarParser.Literal_expressionContext?): Any {
        return when(val content = visit(ctx?.getChild(0))) {
            is Int -> IntegerL(Variability.CST, content, typeTable["Integer"]!!)
            is Double -> DoubleL(Variability.CST, content, typeTable["Double"]!!)
            is Boolean -> BooleanL(Variability.CST, content, typeTable["Bool"]!!)
            is Pair<*, *> ->
                when (content.second) {
                    _STRING -> StringL(Variability.CST, content.first as String, typeTable["String"]!!)
                    _CHARACTER -> CharacterL(Variability.CST, content.first as Char, typeTable["Character"]!!)
                    else -> throw Exception("This cannot happen.")
                }
            is Array<*> -> {
                content as Array<Literal>
                ArrayL(Variability.CST, inferPrimaryToArrayType(content), content.toMutableList(), typeTable["Array"]!!)
            }
            else -> content as Literal
        }
    }

    // TODO Array type check
    override fun visitArray_literal(ctx: amatsukazeGrammarParser.Array_literalContext?): Any {
        if (ctx?.childCount == 2) {
            return Array<Literal?>(0) { null }
        } else {
            val array = Array<Literal?>(ctx?.childCount!! / 2) { null }
            val variability =
                if (visit(ctx?.parent?.parent?.parent?.getChild(0)) == "let") Variability.CST else Variability.VAR
            for (i in 0 until ctx.childCount / 2) {
                array[i] = when (val content = visit(ctx.getChild(i * 2 + 1))) {
                    is IntegerL -> IntegerL(variability, content.content, content.prototype)
                    is DoubleL -> DoubleL(variability, content.content, content.prototype)
                    is BooleanL -> BooleanL(variability, content.content, content.prototype)
                    is StringL -> StringL(variability, content.content, content.prototype)
                    is CharacterL -> CharacterL(variability, content.content, content.prototype)
                    is StructL -> StructL(variability, content.body, content.prototype)
                    is FunctionL -> FunctionL(
                        variability,
                        content.head,
                        content.body,
                        content.closureScope,
                        content.prototype
                    )
                    is ArrayL -> ArrayL(variability, inferArrayType(content), content.content, content.prototype)
                    else -> throw Exception("This should not happen.")
                }
            }
            return array
        }
    }

    override fun visitArray_literal_item(ctx: amatsukazeGrammarParser.Array_literal_itemContext?): Any {
        return visit(ctx?.expression())
    }

    override fun visitMember_expression(ctx: amatsukazeGrammarParser.Member_expressionContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitSubscript_expression(ctx: amatsukazeGrammarParser.Subscript_expressionContext?): Any {
        val left = visit(ctx?.getChild(0))
        val right = visit(ctx?.subscript())
        if (ctx?.variable_expression() != null || ctx?.subscript_expression() != null) {
            if (right is Int) {
                left as ArrayL
                return left.content[right]
            } else {
                throw Exception("Unsupported operation on subscript expression.")
            }
        } else {
            throw Exception("Unsupported subscript operation on structures.")
        }
    }

    override fun visitSubscript(ctx: amatsukazeGrammarParser.SubscriptContext?): Any {
        return if (ctx?.DECIMAL_LITERAL() == null) ctx?.IDENTIFIER()?.text ?: throw Exception("Null identifier encountered")
        else "${visit(ctx?.DECIMAL_LITERAL())}".toInt()
    }

    override fun visitVariable_expression(ctx: amatsukazeGrammarParser.Variable_expressionContext?): Any {
        try {
            return when (val name = visit(ctx?.IDENTIFIER()).toString()) {
                "isOnGem" -> manager.isOnGem(manager.firstId)
                "isOnOpenedSwitch" -> manager.isOnOpenedSwitch(manager.firstId)
                "isOnClosedSwitch" -> manager.isOnClosedSwitch(manager.firstId)
                "isBlocked" -> manager.isBlocked(manager.firstId)
                "isBlockedLeft" -> manager.isBlockedLeft(manager.firstId)
                "isBlockedRight" -> manager.isBlockedRight(manager.firstId)
                "collectedGem" -> manager.collectedGem(manager.firstId)
                else -> {
                    return queryVariableTable(name).first!!
                }
            }
        } catch (e: Exception) {
            throw Exception("The variable looking for doesn't exist!")
        }
    }

    override fun visitFunction_call_expression(ctx: amatsukazeGrammarParser.Function_call_expressionContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitCall_argument_clause(ctx: amatsukazeGrammarParser.Call_argument_clauseContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitCall_argument(ctx: amatsukazeGrammarParser.Call_argumentContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitStruct_call_expression(ctx: amatsukazeGrammarParser.Struct_call_expressionContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitDot_operator(ctx: amatsukazeGrammarParser.Dot_operatorContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitStatement(ctx: amatsukazeGrammarParser.StatementContext?): Any {
        return visit(ctx?.getChild(0))
    }

    override fun visitStatements(ctx: amatsukazeGrammarParser.StatementsContext?): Any {
        try {
            if (inFunc.isNotEmpty()) internalDepth += 1 else externalDepth += 1
            var ret: Any = SpecialRetVal.Statements
            for (child in ctx?.children!!) {
                if (_break || _continue)
                    break
                ret = visit(child)
            }
            if (inFunc.isNotEmpty()) {
                internalVariableDepth.filter { it.value == internalDepth }.keys.forEach { name -> internalVariables[currentFunc].remove(name) }
                internalVariableDepth.filter { it.value == internalDepth }.keys.forEach { name -> internalVariableDepth.remove(name) }
                internalDepth -= 1
            } else {
                variableDepth.filter { it.value == externalDepth }.keys.forEach { name -> variableTable.remove(name) }
                variableDepth.filter { it.value == externalDepth }.keys.forEach { name -> variableDepth.remove(name) }
                externalDepth -= 1
            }
            return ret
        } catch (e: Throwable) {
            if (e is ReturnedLiteral) {
                internalDepth = funcEntriesDepth.last()
                internalVariableDepth.filter { it.value > internalDepth }.keys.forEach { name -> internalVariables[currentFunc].remove(name) }
                internalVariableDepth.filter { it.value > internalDepth }.keys.forEach { name -> internalVariableDepth.remove(name) }
                funcEntriesDepth.removeAt(funcEntriesDepth.lastIndex)
                throw e
            }
            else
                throw Exception("Something goes wrong while visiting statements:\n    ${e.message}")
        }
    }

    override fun visitLoop_statement(ctx: amatsukazeGrammarParser.Loop_statementContext?): Any {
        return visit(ctx?.getChild(0))
    }

    override fun visitFor_in_statement(ctx: amatsukazeGrammarParser.For_in_statementContext?): Any {
        try {
            val preRange = visit(ctx?.expression())
            var range: Array<Any> = (0 .. 0).toList().toTypedArray()
            when (preRange) {
                is IntRange -> range = preRange.toList().toTypedArray()
                is ArrayL -> {
                    range = preRange.content.toTypedArray()
                }
                is String -> {
                    val literal = queryVariableTable(preRange).first ?: throw Exception("Variable not found")
                    if (literal is ArrayL) {
                        range = literal.content.toTypedArray()
                    }
                }
            }
            // Future possibility: range of an object
            val pattern = visit(ctx?.pattern()) as String
            // println(range)
            for (x in range) {
                if (pattern != "_") {
                    when (x) {
                        is Int -> variableTable[pattern] = IntegerL(Variability.VAR, x, typeTable["Integer"]!!)
                        is Double -> variableTable[pattern] = DoubleL(Variability.VAR, x, typeTable["Double"]!!)
                        is String -> variableTable[pattern] = StringL(Variability.VAR, x, typeTable["String"]!!)
                        is Char -> variableTable[pattern] = CharacterL(Variability.VAR, x, typeTable["Character"]!!)
                        is Boolean -> variableTable[pattern] = BooleanL(Variability.VAR, x, typeTable["Bool"]!!)
                        else -> throw Exception("Unsupported operation on for-in statement")
                        // TODO this might cause problem if the pattern shallows another variable in the outer scope?
                    }
                }
                if (_break) {
                    _break = false
                    break
                }
                if (_continue) {
                    _continue = false
                    continue
                }
                visit(ctx?.code_block())
            }
            variableTable.remove(pattern)
        } catch (e: Exception) {
            throw Exception("Something goes wrong while visiting for-in expression:\n    ${e.message}")
        }
        return SpecialRetVal.Loop
    }

    override fun visitWhile_statement(ctx: amatsukazeGrammarParser.While_statementContext?): Any {
        val cond = visit(ctx?.expression()) as BooleanL
        while (cond.content) {
            if (_break) {
                _break = false
                break
            }
            if (_continue) {
                _continue = false
                break
            }
            visit(ctx?.code_block())
        }
        return SpecialRetVal.Loop
    }

    override fun visitRepeat_while_statement(ctx: amatsukazeGrammarParser.Repeat_while_statementContext?): Any {
        do {
            if (_break) {
                _break = false
                break
            }
            if (_continue) {
                _continue = false
                continue
            }
            visit(ctx?.code_block())
        } while (visit(ctx?.expression()) == true)
        return SpecialRetVal.Loop
    }

    override fun visitBranch_statement(ctx: amatsukazeGrammarParser.Branch_statementContext?): Any {
        return visit(ctx?.if_statement())
    }

    override fun visitIf_statement(ctx: amatsukazeGrammarParser.If_statementContext?): Any {
        if (visit(ctx?.expression()) == true) {
            visit(ctx?.code_block())
        } else {
            if (ctx?.else_clause() != null) {
                visit(ctx.else_clause())
            }
        }
        return SpecialRetVal.Branch
    }

    override fun visitElse_clause(ctx: amatsukazeGrammarParser.Else_clauseContext?): Any {
        return visit(ctx?.getChild(1))
    }

    override fun visitControl_transfer_statement(ctx: amatsukazeGrammarParser.Control_transfer_statementContext?): Any {
        return visit(ctx?.getChild(0))
    }

    override fun visitBreak_statement(ctx: amatsukazeGrammarParser.Break_statementContext?): Any {
        _break = true
        return SpecialRetVal.Interr
    }

    override fun visitContinue_statement(ctx: amatsukazeGrammarParser.Continue_statementContext?): Any {
        _continue = true
        return SpecialRetVal.Interr
    }

    override fun visitReturn_statement(ctx: amatsukazeGrammarParser.Return_statementContext?): Any {
        val ret = ReturnedL(visit(ctx?.expression()))
        throw ret
    }

    override fun visitYield_statement(ctx: amatsukazeGrammarParser.Yield_statementContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitConstantDecl(ctx: amatsukazeGrammarParser.ConstantDeclContext?): Any {
        return visit(ctx?.constant_declaration())
    }

    override fun visitVariableDecl(ctx: amatsukazeGrammarParser.VariableDeclContext?): Any {
        return visit(ctx?.variable_declaration())
    }

    override fun visitFunctionDecl(ctx: amatsukazeGrammarParser.FunctionDeclContext?): Any {
        return visit(ctx?.function_declaration())
    }

    override fun visitEnumDecl(ctx: amatsukazeGrammarParser.EnumDeclContext?): Any {
        return visit(ctx?.enum_declaration())
    }

    override fun visitStructDecl(ctx: amatsukazeGrammarParser.StructDeclContext?): Any {
        return visit(ctx?.struct_declaration())
    }

    override fun visitCode_block(ctx: amatsukazeGrammarParser.Code_blockContext?): Any {
        return visit(ctx?.statements())
    }

    override fun visitConstant_declaration(ctx: amatsukazeGrammarParser.Constant_declarationContext?): Any {
        val left = visit(ctx?.pattern()) as String
        if (!(left[0].isLetter() || left[0] == '_')) throw Exception("Illegal variable name")
        try {
            val right = visit(ctx?.expression())
            right as Literal
            val type = if (ctx?.childCount == 4) null else visit(ctx?.type()) as DataType?
            if (ctx?.parent?.parent?.parent?.parent?.parent is amatsukazeGrammarParser.Function_bodyContext) {
                internalVariables[internalVariables.size - 1][left] =
                    declareConstantOrVariable(type, right, constant = true) ?: throw Exception("Encountered error while declaring constant")
                internalVariableDepth[left] = internalDepth
                return SpecialRetVal.Declaration
            } else {
                variableTable[left] =
                    declareConstantOrVariable(type, right, constant = true) ?: throw Exception("Encountered error while declaring constant")
                variableDepth[left] = externalDepth
                return SpecialRetVal.Declaration
            }
        } catch (e: Exception) {
            throw Exception("Unknown literal type on right-hand side while declaring constant\n    $e")
        }
    }

    override fun visitVariable_declaration(ctx: amatsukazeGrammarParser.Variable_declarationContext?): Any {
        val left = visit(ctx?.pattern()) as String
        if (!(left[0].isLetter() || left[0] == '_')) throw Exception("Illegal variable name")
        try {
            val right = visit(ctx?.expression()) as Literal
            val type = if (ctx?.childCount == 4) null else visit(ctx?.type()) as DataType?
            if (ctx?.parent?.parent?.parent?.parent?.parent is amatsukazeGrammarParser.Function_bodyContext) {
                internalVariables[internalVariables.size - 1][left] =
                    declareConstantOrVariable(type, right, constant = false)
                        ?: throw Exception("Encountered error while declaring constant")
                internalVariableDepth[left] = internalDepth
                return SpecialRetVal.Declaration
            } else {
                variableTable[left] =
                    declareConstantOrVariable(type, right, constant = false)
                        ?: throw Exception("Encountered error while declaring constant")
                variableDepth[left] = externalDepth
                return SpecialRetVal.Declaration
            }
        } catch (e: Exception) {
            throw Exception("Unknown literal type on right-hand side while declaring variable")
        }
    }

    private fun handleFunctionDeclaration(nameNode: amatsukazeGrammarParser.Function_nameContext?,
                                          signatureNode: amatsukazeGrammarParser.Function_signatureContext,
                                          bodyNode: amatsukazeGrammarParser.Function_bodyContext): Any {
        try {
            val functionSignature = visit(signatureNode) as Pair<List<Triple<String, DataType, Boolean>>, DataType>
            if (nameNode == null) {
                val funcHead = FuncHead(
                    "$#${assignAnonymousFuncIndex(currentFunc)}",
                    functionSignature.first.map { it.first },
                    functionSignature.first.map { it.second },
                    functionSignature.first.map { it.third },
                    functionSignature.second
                )
                for (key in functionTable.keys) {
                    if (funcHead.pseudoEquals(key))
                        throw Exception("Redefined function!")
                }
                functionTable[funcHead] = bodyNode to currentFunc
                return funcHead
            } else {
                val funcHead = FuncHead(
                    visit(nameNode) as String,
                    functionSignature.first.map { it.first },
                    functionSignature.first.map { it.second },
                    functionSignature.first.map { it.third },
                    functionSignature.second
                )
                for (key in functionTable.keys)
                    if (funcHead.pseudoEquals(key))
                        throw Exception("Redefined function!")
                functionTable[funcHead] = bodyNode to currentFunc
                return funcHead
            }
        } catch (e: Exception) {
            throw Exception("Encountered error within function declaration: \n    ${e.message}")
        }
    }

    override fun visitFunction_declaration(ctx: amatsukazeGrammarParser.Function_declarationContext?): Any {
        return handleFunctionDeclaration(ctx!!.function_name(), ctx.function_signature(), ctx.function_body())
    }

    override fun visitFunction_name(ctx: amatsukazeGrammarParser.Function_nameContext?): Any {
        return ctx?.IDENTIFIER().toString()
    }

    override fun visitFunction_signature(ctx: amatsukazeGrammarParser.Function_signatureContext?): Any {
        val paramClause = visit(ctx?.parameter_clause()) as List<Triple<String, DataType, Boolean>>
        val resultType = if (ctx?.childCount == 1) _VOID else visit(ctx?.function_result_type())
        return paramClause to resultType as Pair<List<Triple<String, DataType, Boolean>>, DataType>
    }

    override fun visitFunction_result_type(ctx: amatsukazeGrammarParser.Function_result_typeContext?): Any {
        return visit(ctx?.type())
    }

    override fun visitFunction_body(ctx: amatsukazeGrammarParser.Function_bodyContext?): Any {
        return visit(ctx?.code_block())
    }

    override fun visitExpressional_function_declaration(ctx: amatsukazeGrammarParser.Expressional_function_declarationContext?): Any {
        return if (ctx?.function_declaration() == null) visit(ctx?.arrowfun_declaration()) else visit(ctx.function_declaration())
    }

    override fun visitArrowfun_declaration(ctx: amatsukazeGrammarParser.Arrowfun_declarationContext?): Any {
        return handleFunctionDeclaration(null, ctx!!.function_signature(), ctx.function_body())
    }

    override fun visitParameter_clause(ctx: amatsukazeGrammarParser.Parameter_clauseContext?): Any {
        return visit(ctx?.parameter_list())
    }

    override fun visitParameter_list(ctx: amatsukazeGrammarParser.Parameter_listContext?): Any {
        return ctx?.children?.map { visit(it) }!!
    }

    override fun visitParameter(ctx: amatsukazeGrammarParser.ParameterContext?): Any {
        val ref = ctx?.REF() != null
        return Triple(visit(ctx?.param_name()), visit(ctx?.type_annotation()), ref) as Triple<String, DataType, Boolean>
    }

    override fun visitParam_name(ctx: amatsukazeGrammarParser.Param_nameContext?): Any {
        return ctx?.IDENTIFIER().toString()
    }

    override fun visitEnum_declaration(ctx: amatsukazeGrammarParser.Enum_declarationContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitEnum_name(ctx: amatsukazeGrammarParser.Enum_nameContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitEnum_members(ctx: amatsukazeGrammarParser.Enum_membersContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitEnum_member(ctx: amatsukazeGrammarParser.Enum_memberContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitStruct_declaration(ctx: amatsukazeGrammarParser.Struct_declarationContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitStruct_name(ctx: amatsukazeGrammarParser.Struct_nameContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitStruct_body(ctx: amatsukazeGrammarParser.Struct_bodyContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitStruct_initializer(ctx: amatsukazeGrammarParser.Struct_initializerContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitAttribute_assignment(ctx: amatsukazeGrammarParser.Attribute_assignmentContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitStruct_member(ctx: amatsukazeGrammarParser.Struct_memberContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitType_inheritance_clause(ctx: amatsukazeGrammarParser.Type_inheritance_clauseContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitType_annotation(ctx: amatsukazeGrammarParser.Type_annotationContext?): Any {
        return visit(ctx?.type())
    }

    override fun visitPattern(ctx: amatsukazeGrammarParser.PatternContext?): Any {
        return visit(ctx?.getChild(0))
    }

    override fun visitWildcard_pattern(ctx: amatsukazeGrammarParser.Wildcard_patternContext?): Any {
        return "_"
    }

    override fun visitIdentifier_pattern(ctx: amatsukazeGrammarParser.Identifier_patternContext?): Any {
        return ctx?.IDENTIFIER()?.text ?: throw Exception("Empty identifier encountered")
    }

    override fun visitNumberedExpMemberExpr(ctx: amatsukazeGrammarParser.NumberedExpMemberExprContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitNamedExpMemberExpr(ctx: amatsukazeGrammarParser.NamedExpMemberExprContext?): Any {
        // Internal functions such as foo.isOnGem
        TODO("Not yet implemented")
    }

    override fun visitFuncExpMemberExpr(ctx: amatsukazeGrammarParser.FuncExpMemberExprContext?): Any {
        return handleFunctionCall(ctx?.function_call_expression()!!, ctx.variable_expression())
    }

    override fun visitMemberOfFuncCallExpr(ctx: amatsukazeGrammarParser.MemberOfFuncCallExprContext?): Any {
        TODO("Not yet implemented")
    }

    override fun visitRecursiveExpMemberExpr(ctx: amatsukazeGrammarParser.RecursiveExpMemberExprContext?): Any {
        // Internal functions call such as foo.bar.isOnGem
        TODO("Not yet implemented")
    }

    override fun visitSimpleType(ctx: amatsukazeGrammarParser.SimpleTypeContext?): Any {
        return when (ctx?.IDENTIFIER().toString()) {
            "Int" -> _INT
            "Double" -> _DOUBLE
            "Character" -> _CHARACTER
            "String" -> _STRING
            "Bool" -> _BOOL
            "Void" -> _VOID
            "Function" -> _FUNCTION
            "Struct" -> _STRUCT
            "Enum" -> _ENUM
            "Array" -> _ARRAY(_SOME)
            else -> throw Exception("Unsupported type")
        }
    }

    override fun visitArrayType(ctx: amatsukazeGrammarParser.ArrayTypeContext?): Any {
        return _ARRAY(visit(ctx?.type()) as DataType)
    }

    override fun visitArrayTypeSub(ctx: amatsukazeGrammarParser.ArrayTypeSubContext?): Any {
        return _ARRAY(visit(ctx?.type()) as DataType)
    }

    override fun visitMember_pattern(ctx: amatsukazeGrammarParser.Member_patternContext?): Any {
        when {
            ctx?.identifier_pattern() != null -> {
                val variable = (queryVariableTable(ctx?.identifier_pattern()?.text!!) as Pair<*, *>).first
                    ?: throw Exception("Cannot find variable associated to identifier")
                return if (ctx?.IDENTIFIER()?.text == "prototype")
                    (variable as Proto).prototype ?: throw Exception("Encountered null while searching in prototype chain")
                else {
                    val attribute = ctx.IDENTIFIER()?.text!!
                    when (variable) {
                        is StructL -> {
                            if (variable.body.containsKey(attribute)) variable.body[attribute]!!
                            else variable to attribute
                        }
                        else -> throw Exception("Unsupported member pattern call")
                    }
                }
            }
            ctx?.member_pattern() != null -> {
                when (val variable = (queryVariableTable(ctx?.identifier_pattern()?.text!!) as Pair<*, *>).first
                    ?: throw Exception("Cannot find variable associated to identifier")) {
                    is Proto -> {
                        if (ctx?.IDENTIFIER()?.text == "prototype")
                            return variable.prototype ?: throw Exception("Encountered null while searching in prototype chain")
                        else {
                            val attribute = ctx.IDENTIFIER()?.text!!
                            return when (variable) {
                                is StructL -> {
                                    if (variable.body.containsKey(attribute)) variable.body[attribute]!!
                                    else variable to attribute
                                }
                                is Prototype -> {
                                    if (variable.members.containsKey(attribute)) variable.members[attribute]!!
                                    else variable to attribute
                                }
                                else -> throw Exception("Unsupported member pattern call")
                            }
                        }
                    }
                    is Pair<*, *> -> throw Exception("Unsupported operation on member pattern")
                    else -> throw Exception("This could not happen")
                }
            }
            else -> throw Exception("This could not happen")
        }
    }

    override fun visitSubscript_pattern(ctx: amatsukazeGrammarParser.Subscript_patternContext?): Any {
        if (ctx?.identifier_pattern() != null) {
            val left = queryVariableTable(visit(ctx?.getChild(0)) as String)
            val right = visit(ctx?.subscript())
            if (left.second != -2) {
                if (left.first is ArrayL && right is Int)
                    return (left.first as ArrayL).content[right]
                if (left.first is StructL)
                    throw Exception("Unsupported subscript operation on structures.")
            }
            throw Exception("Unsupported lhs subscript operation.")
        }
        if (ctx?.subscript_pattern() != null) {
            val left = visit(ctx?.subscript_pattern())
            val right = visit(ctx?.subscript())
            if (left is ArrayL && right is Int) return left.content[right]
            throw Exception("Unsupported lhs subscript operation.")
        }
        if (ctx?.member_expression() != null) {
            // TODO
        }
        throw Exception("This cannot happen")
    }

    override fun visitAssert_statement(ctx: amatsukazeGrammarParser.Assert_statementContext?): Any {
        when (val rhs = visit(ctx?.expression())) {
            is IntegerL -> if (rhs.content == 0) throw Exception("AssertError\n")
            is DoubleL -> if (rhs.content == 0.0) throw Exception("AssertError\n")
            is BooleanL -> if (!rhs.content) throw Exception("AssertError\n")
            else -> throw Exception("AssertError\n")
        }
        return SpecialRetVal.Empty
    }

}