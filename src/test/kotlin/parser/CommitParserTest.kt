package parser

import data.Commit
import org.joda.time.DateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommitParserTest {

  private val goodCommits =
    "34a1f35a27006dd379c8b00bcdf64d1bf4344824; 2019-09-15T20:04:09+03:00; trigger CI build 1\n" +
      "f331d427addf7e7324e077b91b5043594298ed82; 2019-09-15T19:54:42+03:00; trigger CI build 2\n" +
      "4c97a4587bb30d3fe4cb8997ee6d3034b390edba; 2019-09-15T19:28:20+03:00; trigger CI build 3\n" +
      "10809f356ca657d222d678728aada9b0f52d51c6; 2019-09-15T19:11:58+03:00; trigger CI build 4\n" +
      "63b13b3d73fd9487517e858ce204f302e3b9b092; 2019-09-15T18:11:58+03:00; trigger CI build 5\n" +
      "f9aeb7a96f90943f184b1a2701c69e7b109316c7; 2019-09-15T18:07:57+03:00; trigger CI build 6\n" +
      "974a9a48dad7fcd0c3e13ec3267d36ad0029c5cc; 2019-09-15T17:53:03+03:00; trigger CI build 7\n" +
      "781b4fc8c575281859d73029232ce10318cb70e7; 2019-09-15T17:42:52+03:00; trigger CI build 8\n" +
      "d5b07fec46a3edfb8e4e5cc94de68118d0d46704; 2019-09-15T17:29:58+03:00; trigger CI build 9\n" +
      "c945f7a8a5b866622535df1a417ab13a71b89fe1; 2019-09-15T17:25:21+03:00; trigger CI build 10\n" +
      "14bd8819810c51f60adc9b910b4721c6781e793d; 2019-09-11T23:46:07-07:00; v4.9.2; please backup before downloading\n"

  private val badCommits =
    "" +
      ";;;" +
      "\n" +
      " " +
      "AWFAWG; 2019-09-15 20:04:09; trigger CI build 1\n" +
      "; 2019-09-15 19:54:42; trigger CI build 2\n" +
      "4c97a4587bb30d3fe4cb8997ee6d3034b390edba; ; trigger CI build 3\n" +
      "10809f356ca657d222d678728aada9b0f52d51c6; 2019-09-15 19:11:58; \n" +
      "63b13b3d73fd9487517e858ce204f302e3b9b092; dfhdfj; trigger CI build 5\n" +
      "f9aeb7a96f90943f184b1a2701c69e7b109316c7; 2019-99-15 18:07:57; trigger CI build 6\n" +
      "974a9a48dad7fcd0c3e13ec3267d36ad0029c5ccWF; 2019-09-15 17:53:03; trigger CI build 7\n" +
      "d5b07FEC46a3edfb8e4e5cc94de68118d0d46704; 2019-09-15 17:53:03; trigger CI build 8\n" +
      "c945f7a8a5b866622535df1a417ab13a71b89fe1; 2019-09-15T17:25:21+03:00; trigger CI build good\n"

  private val commitParser = CommitParser()

  @Test
  fun `parse bad commits`() {
    val commits = commitParser.parseCommits(444000, badCommits)
    assertEquals(1, commits.size)

    assertEquals("c945f7a8a5b866622535df1a417ab13a71b89fe1", commits[0].commitHash)
    assertEquals(
      DateTime.parse("2019-09-15T17:25:21+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[0].committedAt
    )

    assertEquals("trigger CI build good", commits[0].description)
    assertTrue(commits[0].head)
  }

  @Test
  fun `parse good commits`() {
    val commits = commitParser.parseCommits(444000, goodCommits)

    assertEquals(11, commits.size)

    assertEquals("34a1f35a27006dd379c8b00bcdf64d1bf4344824", commits[0].commitHash)
    assertEquals("f331d427addf7e7324e077b91b5043594298ed82", commits[1].commitHash)
    assertEquals("4c97a4587bb30d3fe4cb8997ee6d3034b390edba", commits[2].commitHash)
    assertEquals("10809f356ca657d222d678728aada9b0f52d51c6", commits[3].commitHash)
    assertEquals("63b13b3d73fd9487517e858ce204f302e3b9b092", commits[4].commitHash)
    assertEquals("f9aeb7a96f90943f184b1a2701c69e7b109316c7", commits[5].commitHash)
    assertEquals("974a9a48dad7fcd0c3e13ec3267d36ad0029c5cc", commits[6].commitHash)
    assertEquals("781b4fc8c575281859d73029232ce10318cb70e7", commits[7].commitHash)
    assertEquals("d5b07fec46a3edfb8e4e5cc94de68118d0d46704", commits[8].commitHash)
    assertEquals("c945f7a8a5b866622535df1a417ab13a71b89fe1", commits[9].commitHash)
    assertEquals("14bd8819810c51f60adc9b910b4721c6781e793d", commits[10].commitHash)

    assertEquals(
      DateTime.parse("2019-09-15T20:04:09+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[0].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T19:54:42+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[1].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T19:28:20+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[2].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T19:11:58+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[3].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T18:11:58+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[4].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T18:07:57+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[5].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T17:53:03+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[6].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T17:42:52+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[7].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T17:29:58+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[8].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15T17:25:21+03:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[9].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-11T23:46:07-07:00", Commit.COMMIT_DATE_TIME_PARSER),
      commits[10].committedAt
    )

    assertEquals("trigger CI build 1", commits[0].description)
    assertEquals("trigger CI build 2", commits[1].description)
    assertEquals("trigger CI build 3", commits[2].description)
    assertEquals("trigger CI build 4", commits[3].description)
    assertEquals("trigger CI build 5", commits[4].description)
    assertEquals("trigger CI build 6", commits[5].description)
    assertEquals("trigger CI build 7", commits[6].description)
    assertEquals("trigger CI build 8", commits[7].description)
    assertEquals("trigger CI build 9", commits[8].description)
    assertEquals("trigger CI build 10", commits[9].description)
    assertEquals("v4.9.2; please backup before downloading", commits[10].description)

    assertTrue(commits[0].head)
    assertFalse(commits[1].head)
    assertFalse(commits[2].head)
    assertFalse(commits[3].head)
    assertFalse(commits[4].head)
    assertFalse(commits[5].head)
    assertFalse(commits[6].head)
    assertFalse(commits[7].head)
    assertFalse(commits[8].head)
    assertFalse(commits[9].head)
    assertFalse(commits[10].head)
  }

  @Test
  fun `test datetime formatter printing`() {
    val original = "2019-09-15 17:42:52 UTC"

    assertThrows(IllegalArgumentException::class.java) {
      DateTime.parse(original, Commit.COMMIT_DATE_TIME_PARSER)
    }
  }

  @Test
  fun `old datetime formatter should fail`() {
    val parsed = DateTime.parse("2019-09-15T17:42:52+03:00", Commit.COMMIT_DATE_TIME_PARSER)
    assertEquals("2019-09-15 14:42:52 UTC", Commit.COMMIT_DATE_TIME_PRINTER.print(parsed))
  }
}