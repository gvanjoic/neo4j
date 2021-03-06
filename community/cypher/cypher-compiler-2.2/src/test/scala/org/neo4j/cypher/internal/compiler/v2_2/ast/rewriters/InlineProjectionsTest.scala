/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.cypher.internal.compiler.v2_2.ast.rewriters

import org.neo4j.cypher.internal.commons.CypherFunSuite
import org.neo4j.cypher.internal.compiler.v2_2.ast._
import org.neo4j.cypher.internal.compiler.v2_2.inSequence
import org.neo4j.cypher.internal.compiler.v2_2.planner.{AstRewritingTestSupport, CantHandleQueryException}

class InlineProjectionsTest extends CypherFunSuite with AstRewritingTestSupport {

  test("should inline: MATCH a, b, c WITH c AS c, b AS a RETURN c") {
    val result = projectionInlinedAst("MATCH a, b, c WITH c AS c, b AS d RETURN c")

    result should equal(ast("MATCH a, b, c WITH c AS c, b AS b RETURN c AS c"))
  }

  test("should inline: WITH {b} AS tmp, {r} AS r WITH {a} AS b AS a, r LIMIT 1 MATCH (a)-[r]->(b) RETURN a, r, b") {
    val result = projectionInlinedAst("WITH {a} AS b, {b} AS tmp, {r} AS r WITH b AS a, r LIMIT 1 MATCH (a)-[r]->(b) RETURN a, r, b")

    result should equal(ast("WITH {a} AS b, {r} AS r WITH b AS a, r LIMIT 1 MATCH (a)-[r]->(b) RETURN a, r, b"))
  }

  test("should inline: MATCH a, b, c WITH c AS d, b AS a RETURN d") {
    val result = projectionInlinedAst("MATCH a, b, c WITH c AS d, b AS e RETURN d")

    result should equal(ast("MATCH a, b, c WITH c AS c, b AS b RETURN c AS d"))
  }

  test("should  inline: MATCH n WITH n AS m RETURN m => MATCH n RETURN n") {
    val result = projectionInlinedAst("MATCH n WITH n AS m RETURN m")

    result should equal(ast("MATCH n WITH n RETURN n AS m"))
  }

  test("should  inline: MATCH (a:Start) WITH a.prop AS property LIMIT 1 MATCH (b) WHERE id(b) = property RETURN b") {
    val result = projectionInlinedAst("MATCH (a:Start) WITH a.prop AS property LIMIT 1 MATCH (b) WHERE id(b) = property RETURN b, property")

    result should equal(ast("MATCH (a:Start) WITH a LIMIT 1 MATCH (b) WHERE id(b) = a.prop RETURN b, a.prop AS property"))
  }

  test("should inline: MATCH (a) WITH a WHERE TRUE RETURN a") {
    val result = projectionInlinedAst("MATCH (a) WITH a WHERE TRUE RETURN a")

    result should equal(parser.parse("MATCH (a) WITH a AS a WITH a AS a WHERE true WITH a AS a RETURN a AS a"))
  }

  test("should inline pattern identifiers when possible") {
    val result = projectionInlinedAst("MATCH n WITH n MATCH n-->x RETURN x")

    result should equal(ast("MATCH n WITH n MATCH n-->x RETURN x"))
  }

  test("should inline: WITH 1 AS x RETURN 1 + x => WITH * RETURN 1 + 1") {
    val result = projectionInlinedAst("WITH 1 AS x RETURN 1 + x")

    result should equal(ast("WITH * RETURN 1 + 1 AS `1 + x`"))
  }

  test("should inline: WITH 1 as b RETURN b => RETURN 1 AS `b`") {
    val result = projectionInlinedAst("WITH 1 as b RETURN b")

    result should equal(ast("WITH * RETURN 1 AS `b`"))
  }

  test("should not inline aggregations: WITH 1 as b WITH DISTINCT b AS c RETURN c => WITH DISTINCT 1 AS c RETURN c AS c") {
    val result = projectionInlinedAst("WITH 1 as b WITH DISTINCT b AS c RETURN c")

    result should equal(ast("WITH * WITH DISTINCT 1 AS `c` RETURN c AS `c`"))
  }

  test("should not inline identifiers into patterns: WITH 1 as a MATCH (a) RETURN a => WITH 1 as a MATCH (a) RETURN a AS a") {
    val result = projectionInlinedAst("WITH 1 as a MATCH (a) RETURN a")

    result should equal(ast("WITH 1 as a MATCH (a) RETURN a AS `a`"))
  }

  test("should inline multiple identifiers across multiple WITH clauses: WITH 1 as n WITH n+1 AS m RETURN m => RETURN 1+1 as m") {
    val result = projectionInlinedAst("WITH 1 as n WITH n + 1 AS m RETURN m")

    result should equal(ast("WITH * WITH * RETURN 1+1 as `m`"))
  }

  test("should inline node patterns: MATCH (a) WITH a as b MATCH (b) RETURN b => MATCH (a) WITH * MATCH (a) RETURN a as `b`") {
    val result = projectionInlinedAst("MATCH (a) WITH a as b MATCH (b) RETURN b")

    result should equal(ast("MATCH (a) WITH a AS a MATCH (a) RETURN a as `b`"))
  }

  test("should inline relationship patterns: MATCH ()-[a]->() WITH a as b MATCH ()-[b]->() RETURN b => MATCH ()-[a]->() WITH * MATCH ()-[a]->() RETURN a as `b`") {
    val result = projectionInlinedAst("MATCH ()-[a]->() WITH a as b MATCH ()-[b]->() RETURN b")

    result should equal(ast("MATCH ()-[a]->() WITH a AS a MATCH ()-[a]->() RETURN a as `b`"))
  }

  test("should not inline aggregations: MATCH (a)-[r]->() WITH a, count(r) as b RETURN b as `b`") {
    val result = projectionInlinedAst("MATCH (a)-[r]->() WITH a, count(r) as b RETURN b as `b`")
    result should equal(ast("MATCH (a)-[r]->() WITH a, count(r) as b RETURN b as `b`"))
  }

  test("should not inline aggregations: MATCH (a)-[r]->() RETURN a, count(r) as `b`") {
    val result = projectionInlinedAst("MATCH (a)-[r]->() RETURN a, count(r) as `b`")
    result should equal(ast("MATCH (a)-[r]->() RETURN a, count(r) as `b`"))
  }

  // FIXME: 2014-4-30 Davide: No inlining due to missing scope information for the identifiers
  ignore("should not inline identifiers which are reused multiple times: WITH 1 as n WITH 2 AS n RETURN n") {
    val result = projectionInlinedAst("WITH 1 as n WITH 2 AS n RETURN n")

    result should equal(ast("WITH * WITH * RETURN 2 as `n`"))
  }

  // FIXME: 2014-4-30 Davide: This is not yet supported by the inline rewriter due to missing scope information for the identifiers
  ignore("should inline same identifier across multiple WITH clauses, case #1: WITH 1 as n WITH n+1 AS n RETURN n => RETURN 1+1 as n") {
    val result = projectionInlinedAst("WITH 1 as n WITH n+1 AS n RETURN n")

    result should equal(ast("WITH * WITH * RETURN 1+1 as `n`"))
  }

  // FIXME: 2014-4-30 Davide: This is not yet supported by the inline rewriter due to missing scope information for the identifiers
  ignore("should inline same identifier across multiple WITH clauses, case #2: WITH 1 as n WITH n+2 AS m WITH n + m as n RETURN n => RETURN 1+1+2 as n") {
    val result = projectionInlinedAst("WITH 1 as n WITH n+2 AS m WITH n + m as n RETURN n")

    result should equal(ast("WITH * WITH * WITH * RETURN 1+(1+2) as `n`"))
  }

  // FIXME: 2014-4-30 Davide: This is not yet supported by the inline rewriter due to missing scope information for the identifiers
  ignore("should not inline identifiers which cannot be inlined when they are shadowed later on: WITH 1 as n MATCH (n) WITH 2 AS n RETURN n => WITH 1 as n MATCH (n) RETURN 2 as n") {
    val result = projectionInlinedAst("WITH 1 as n MATCH (n) WITH 2 AS n RETURN n")

    result should equal(ast("WITH 1 as n MATCH (n) WITH * RETURN 2 as `n`"))
  }

  // FIXME: 2014-4-30 Stefan: This is not yet supported by the inline rewriter
  test("should refuse to inline queries containing update clauses by throwing CantHandleQueryException") {
    evaluating { projectionInlinedAst("CREATE (n) RETURN n") } should produce[CantHandleQueryException]
  }

  test("MATCH n WITH n.prop AS x WITH x LIMIT 10 RETURN x" ) {
    val result = projectionInlinedAst("MATCH n WITH n.prop AS x WITH x LIMIT 10 RETURN x")

    result should equal(ast("MATCH n WITH n AS n WITH n AS n LIMIT 10 RETURN n.prop AS x"))
  }

  test("MATCH (a:Start) WITH a.prop AS property, count(*) AS count MATCH (b) WHERE id(b) = property RETURN b" ) {
    val result = projectionInlinedAst("MATCH (a:Start) WITH a.prop AS property, count(*) AS count MATCH (b) WHERE id(b) = property RETURN b")

    result should equal(ast("MATCH (a:Start) WITH a.prop AS property, count(*) AS `count` MATCH (b) WHERE id(b) = property RETURN b AS `b`"))
  }

  // TODO: Fix post dedup
  ignore("removes unneeded projection") {
    val query = """MATCH (owner)
                  |WITH owner, COUNT(*) AS xyz
                  |WITH owner, xyz > 0 as collection
                  |WHERE (owner)--()
                  |RETURN owner""".stripMargin
    val result = projectionInlinedAst(query)

    result should equal(parser.parse(
      """MATCH (owner)
        |WITH owner, COUNT(*) AS xyz
        |WITH owner, xyz
        |WHERE (owner)--()
        |RETURN owner
      """.stripMargin))
  }

  test("WITH 1 as b RETURN b") {
    val result = projectionInlinedAst("WITH 1 as b RETURN b")

    result should equal(ast("WITH * RETURN 1 AS b"))
  }

  test( "match n where id(n) IN [0,1,2,3] with n.division AS `n.division`, max(n.age) AS `max(n.age)` with `n.division` AS `n.division`, `max(n.age)` AS `max(n.age)` RETURN `n.division` AS `n.division`, `max(n.age)` AS `max(n.age)` order by `max(n.age)`") {
    val result = projectionInlinedAst("match n where id(n) IN [0,1,2,3] with n.division AS `n.division`, max(n.age) AS `max(n.age)` with `n.division` AS `n.division`, `max(n.age)` AS `max(n.age)` RETURN `n.division` AS `n.division`, `max(n.age)` AS `max(n.age)` order by `max(n.age)`")

    result should equal(ast( "match n where id(n) IN [0,1,2,3] with n.division AS `n.division`, max(n.age) AS `max(n.age)` with `n.division` AS `n.division`, `max(n.age)` AS `max(n.age)` RETURN `n.division` AS `n.division`, `max(n.age)` AS `max(n.age)` order by `max(n.age)`" ))
  }

  private def parseReturnedExpr(queryText: String) =
    projectionInlinedAst(queryText) match {
      case Query(_, SingleQuery(Seq(_, Return(_, ListedReturnItems(Seq(AliasedReturnItem(expr, Identifier("p")))), _, _, _)))) => expr
    }

  private def projectionInlinedAst(queryText: String) = ast(queryText).endoRewrite(inlineProjections)

  private def ast(queryText: String) = {
    val parsed = parser.parse(queryText)
    parsed.endoRewrite(inSequence(normalizeReturnClauses, normalizeWithClauses))
  }
}

