/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.parser.experimental

import scala.collection.immutable.Map
import scala.collection.immutable.HashMap
import scala.collection.immutable.SortedSet
import scala.collection.breakOut
import org.neo4j.cypher.internal.symbols._

case class Symbol(identifiers: Set[ast.Identifier], types: Set[CypherType]) {
  def tokens = identifiers.map(_.token)(breakOut[Set[ast.Identifier], InputToken, SortedSet[InputToken]])
}

object SemanticState {
  val clean = SemanticState(HashMap.empty, HashMap.empty, None)(0)
}

case class SemanticState(
    symbolTable: Map[String, Symbol],
    typeTable: Map[ast.Expression, Set[CypherType]],
    parent: Option[SemanticState])
    (val pass: Int)
{
  def isClean = symbolTable.isEmpty && typeTable.isEmpty
  def isFirstPass = pass == 0
  def nextPass = SemanticState(symbolTable, typeTable, parent)(pass + 1)
  def newScope = SemanticState(HashMap.empty, typeTable, Some(this))(pass)
  def popScope = SemanticState(parent.get.symbolTable, typeTable, parent.get.parent)(pass)

  def symbol(name: String) : Option[Symbol] = symbolTable.get(name) orElse parent.flatMap(_.symbol(name))
  def symbolTypes(name: String) = this.symbol(name).map(_.types)
  def expressionTypes(expression: ast.Expression) = expression match {
    case identifier: ast.Identifier => symbolTypes(identifier.name)
    case _ => typeTable.get(expression)
  }

  def limitExpressionType(expression: ast.Expression, token: InputToken, possibleType: CypherType, possibleTypes: CypherType*) : Either[SemanticError, SemanticState] =
      limitExpressionType(expression, token, (possibleType +: possibleTypes).toSet)

  def limitExpressionType(expression: ast.Expression, token: InputToken, possibleTypes: Set[CypherType]) : Either[SemanticError, SemanticState] = expression match {
    case identifier: ast.Identifier => implicitIdentifier(identifier, possibleTypes)
    case _ => typeTable.get(expression) match {
      case None => {
        Right(updateType(expression, possibleTypes))
      }
      case Some(types) => {
        val inferredTypes = (types mergeUp possibleTypes)
        if (!inferredTypes.isEmpty) {
          Right(updateType(expression, inferredTypes))
        } else {
          val existingTypes = types.formattedString
          val expectedTypes = possibleTypes.formattedString
          Left(SemanticError(s"Type mismatch: expected ${expectedTypes} but was ${existingTypes}", token, expression.token))
        }
      }
    }
  }

  def declareIdentifier(identifier: ast.Identifier, possibleType: CypherType, possibleTypes: CypherType*) : Either[SemanticError, SemanticState] =
      declareIdentifier(identifier, (possibleType +: possibleTypes).toSet)

  def declareIdentifier(identifier: ast.Identifier, possibleTypes: Set[CypherType]) : Either[SemanticError, SemanticState] = {
    symbolTable.get(identifier.name) match {
      case None => {
        Right(updateIdentifier(identifier, possibleTypes, Set(identifier)))
      }
      case Some(symbol) if symbol.identifiers.contains(identifier) => {
        Right(this)
      }
      case Some(symbol) => {
        Left(SemanticError(s"${identifier.name} already declared", identifier.token, symbol.tokens))
      }
    }
  }

  def implicitIdentifier(identifier: ast.Identifier, possibleType: CypherType, possibleTypes: CypherType*) : Either[SemanticError, SemanticState] =
      implicitIdentifier(identifier, (possibleType +: possibleTypes).toSet)

  def implicitIdentifier(identifier: ast.Identifier, possibleTypes: Set[CypherType]) : Either[SemanticError, SemanticState] = {
    this.symbol(identifier.name) match {
      case None => {
        Right(updateIdentifier(identifier, possibleTypes, Set(identifier)))
      }
      case Some(symbol) => {
        val inferredTypes = (symbol.types mergeUp possibleTypes)
        if (!inferredTypes.isEmpty) {
          Right(updateIdentifier(identifier, inferredTypes, symbol.identifiers + identifier))
        } else {
          val existingTypes = symbol.types.formattedString
          val expectedTypes = possibleTypes.formattedString
          Left(SemanticError(
              s"Type mismatch: ${identifier.name} already defined with conflicting type ${existingTypes} (expected ${expectedTypes})",
              identifier.token, symbol.tokens))
        }
      }
    }
  }

  def ensureIdentifierDefined(identifier: ast.Identifier, possibleType: CypherType, possibleTypes: CypherType*) : Either[SemanticError, SemanticState] =
      ensureIdentifierDefined(identifier, (possibleType +: possibleTypes).toSet)

  def ensureIdentifierDefined(identifier: ast.Identifier, possibleTypes: Set[CypherType]) : Either[SemanticError, SemanticState] = {
    this.symbol(identifier.name) match {
      case None => Left(SemanticError(s"${identifier.name} not defined", identifier.token))
      case Some(_) => implicitIdentifier(identifier, possibleTypes)
    }
  }

  def importSymbols(symbols: Map[String, Symbol]) =
      SemanticState(symbolTable ++ symbols, typeTable, parent)(pass)

  private def updateIdentifier(identifier: ast.Identifier, types: Set[CypherType], identifiers: Set[ast.Identifier]) =
      SemanticState(symbolTable + ((identifier.name, Symbol(identifiers, types))), typeTable, parent)(pass)

  private def updateType(expression: ast.Expression, types: Set[CypherType]) =
      SemanticState(symbolTable, typeTable + ((expression, types)), parent)(pass)
}


case class MergeableCypherTypeSet[T <: CypherType](set: Set[T]) {
  def mergeDown(other: Set[CypherType]) : Set[CypherType] = {
    set.foldLeft(Vector.empty[CypherType])((ts, t) => {
      val dt = other.map { _.mergeDown(t) } reduce { (t1, t2) => (t1 mergeUp t2).get }
      ts.filter(_.mergeUp(dt) != Some(dt)) :+ dt
    }).toSet
  }

  def mergeUp(other: Set[CypherType]) : Set[CypherType] = {
    set.flatMap { t => other.flatMap { _ mergeUp t } }
  }
}


case class FormattableCypherTypeSet[T <: CypherType](set: Set[T]) {
  def formattedString : String = {
    val types = set.toIndexedSeq.map(_.toString)
    types.length match {
      case 0 => ""
      case 1 => types.head
      case _ => s"${types.dropRight(1).mkString(", ")} or ${types.last}"
    }
  }
}