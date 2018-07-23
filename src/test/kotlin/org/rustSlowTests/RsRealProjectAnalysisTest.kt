/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustSlowTests

import com.intellij.codeInspection.ex.InspectionToolRegistrar
import org.junit.ComparisonFailure
import org.rust.ide.inspections.RsLocalInspectionTool
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.cargoWorkspace
import org.rust.openapiext.toPsiFile
import java.lang.reflect.Field

class RsRealProjectAnalysisTest : RsRealProjectTestBase() {

    fun `test analyze rustc`() = doTest(RUSTC)
    fun `test analyze Cargo`() = doTest(CARGO)
    fun `test analyze mysql_async`() = doTest(MYSQL_ASYNC)
    fun `test analyze tokio`() = doTest(TOKIO)

    private fun doTest(info: RealProjectInfo) {
        val inspections = InspectionToolRegistrar.getInstance().createTools()
            .map { it.tool }
            .filter { it is RsLocalInspectionTool }
        myFixture.enableInspections(*inspections.toTypedArray())

        println("Opening the project")
        val base = openRealProject(info) ?: return

        println("Collecting files to analyze")
        val filesToCheck = base.descendants
            .filter { it.fileType == RsFileType }
            .filter {
                val file = it.toPsiFile(project)
                file is RsFile && file.crateRoot != null && file.cargoWorkspace != null
            }
//            .filter { it.path.endsWith("/librustc_mir/dataflow/impls/mod.rs") }

        val exceptions = filesToCheck.mapNotNull { file ->
            println("Analyzing " + file.path.substring(base.path.length))
            try {
                myFixture.testHighlighting(
                    /* checkWarnings = */ false,
                    /* checkInfos = */ false,
                    /* checkWeakWarnings = */ false,
                    file
                )
                null
            } catch (e: ComparisonFailure) {
                e
            }
        }

        if (exceptions.isNotEmpty()) {
            error(exceptions.joinToString("\n") {
                THROWABLE_DETAILED_MESSAGE_FIELD.get(it) as CharSequence
            })
        }
    }

    companion object {
        private val THROWABLE_DETAILED_MESSAGE_FIELD: Field =
            Throwable::class.java.getDeclaredField("detailMessage")

        init {
            THROWABLE_DETAILED_MESSAGE_FIELD.isAccessible = true
        }
    }
}
