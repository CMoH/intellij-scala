package org.jetbrains.plugins.scala.testingSupport.scalatest.singleTest

import org.jetbrains.plugins.scala.testingSupport.scalatest.ScalaTestTestCase
import org.junit.Assert._

trait FlatSpecSingleTestTestBase extends ScalaTestTestCase {
  protected def doTest(fileName: String, testClassName: String)
                      (lineNumber: Int, offset: Int)
                      (expectedTestName: String, expectedTestPath: List[String]): Unit = {
    runTestByLocation(
      lineNumber, offset, fileName,
      configAndSettings => {
        assertConfigAndSettings(configAndSettings, testClassName, expectedTestName)
        true
      },
      root => {
        assertTrue(
          s"result tree doesn't contain test name with path: $expectedTestPath",
          checkResultTreeHasExactNamedPath(root, expectedTestPath: _*)
        )
        val unexpectedTestName = "should not run other tests"
        assertTrue(
          s"result tree contained unexpected test name: `$unexpectedTestName`",
          checkResultTreeDoesNotHaveNodes(root, unexpectedTestName)
        )
        true
      }
    )
  }
}
