package extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test


class ExtensionsKtTest {

  @Test
  fun `test trimEndIfLongerThan`() {
    val testString = "1234"

    assertEquals(4, testString.length)
    assertEquals(4, testString.trimEndIfLongerThan(4).length)
    assertEquals(3, testString.trimEndIfLongerThan(3).length)
    assertEquals(2, testString.trimEndIfLongerThan(2).length)
    assertEquals(1, testString.trimEndIfLongerThan(1).length)
  }
}