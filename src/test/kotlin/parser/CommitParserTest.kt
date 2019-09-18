package parser

import data.ApkVersion
import data.Commit
import org.joda.time.DateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommitParserTest {

  private val goodCommits =
      "34a1f35a27006dd379c8b00bcdf64d1bf4344824; 2019-09-15 20:04:09; trigger CI build 1\n" +
      "f331d427addf7e7324e077b91b5043594298ed82; 2019-09-15 19:54:42; trigger CI build 2\n" +
      "4c97a4587bb30d3fe4cb8997ee6d3034b390edba; 2019-09-15 19:28:20; trigger CI build 3\n" +
      "10809f356ca657d222d678728aada9b0f52d51c6; 2019-09-15 19:11:58; trigger CI build 4\n" +
      "63b13b3d73fd9487517e858ce204f302e3b9b092; 2019-09-15 18:11:58; trigger CI build 5\n" +
      "f9aeb7a96f90943f184b1a2701c69e7b109316c7; 2019-09-15 18:07:57; trigger CI build 6\n" +
      "974a9a48dad7fcd0c3e13ec3267d36ad0029c5cc; 2019-09-15 17:53:03; trigger CI build 7\n" +
      "781b4fc8c575281859d73029232ce10318cb70e7; 2019-09-15 17:42:52; trigger CI build 8\n" +
      "d5b07fec46a3edfb8e4e5cc94de68118d0d46704; 2019-09-15 17:29:58; trigger CI build 9\n" +
      "c945f7a8a5b866622535df1a417ab13a71b89fe1; 2019-09-15 17:25:21; trigger CI build 10\n"

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
      "c945f7a8a5b866622535df1a417ab13a71b89fe1; 2019-09-15 17:25:21; trigger CI build good\n"

  private val commitParser = CommitParser()

  @Test
  fun `parse bad commits`() {
    val commits = commitParser.parseCommits(ApkVersion(444000), badCommits)

    assertEquals(1, commits.size)

    assertEquals("c945f7a8a5b866622535df1a417ab13a71b89fe1", commits[0].commitHash.hash)
    assertEquals(
      DateTime.parse("2019-09-15 17:25:21", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[0].committedAt
    )
    assertEquals("trigger CI build good", commits[0].description)
  }

  @Test
  fun `parse good commits`() {
    val commits = commitParser.parseCommits(ApkVersion(444000), goodCommits)

    assertEquals(10, commits.size)

    assertEquals("34a1f35a27006dd379c8b00bcdf64d1bf4344824", commits[0].commitHash.hash)
    assertEquals("f331d427addf7e7324e077b91b5043594298ed82", commits[1].commitHash.hash)
    assertEquals("4c97a4587bb30d3fe4cb8997ee6d3034b390edba", commits[2].commitHash.hash)
    assertEquals("10809f356ca657d222d678728aada9b0f52d51c6", commits[3].commitHash.hash)
    assertEquals("63b13b3d73fd9487517e858ce204f302e3b9b092", commits[4].commitHash.hash)
    assertEquals("f9aeb7a96f90943f184b1a2701c69e7b109316c7", commits[5].commitHash.hash)
    assertEquals("974a9a48dad7fcd0c3e13ec3267d36ad0029c5cc", commits[6].commitHash.hash)
    assertEquals("781b4fc8c575281859d73029232ce10318cb70e7", commits[7].commitHash.hash)
    assertEquals("d5b07fec46a3edfb8e4e5cc94de68118d0d46704", commits[8].commitHash.hash)
    assertEquals("c945f7a8a5b866622535df1a417ab13a71b89fe1", commits[9].commitHash.hash)

    assertEquals(
      DateTime.parse("2019-09-15 20:04:09", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[0].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 19:54:42", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[1].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 19:28:20", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[2].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 19:11:58", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[3].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 18:11:58", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[4].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 18:07:57", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[5].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 17:53:03", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[6].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 17:42:52", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[7].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 17:29:58", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[8].committedAt
    )
    assertEquals(
      DateTime.parse("2019-09-15 17:25:21", Commit.COMMIT_DATE_TIME_FORMAT),
      commits[9].committedAt
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
  }
}