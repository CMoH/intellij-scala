package org.jetbrains.plugins.scala
package worksheet.actions

import com.intellij.execution._
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.process.{OSProcessHandler, ProcessAdapter, ProcessEvent}
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.util.EditorHelper
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent}
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.compiler.{CompileContext, CompileStatusNotification, CompilerManager}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.{KeymapManager, KeymapUtil}
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.{JavaSdkType, JdkUtil}
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Key
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import javax.swing.Icon
import org.jetbrains.plugins.scala.extensions.{inWriteAction, invokeAndWait, invokeLater}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.project._
import org.jetbrains.plugins.scala.statistics.{FeatureKey, Stats}
import org.jetbrains.plugins.scala.worksheet.processor.WorksheetCompiler
import org.jetbrains.plugins.scala.worksheet.runconfiguration.WorksheetCache
import org.jetbrains.plugins.scala.worksheet.server.WorksheetProcessManager
import org.jetbrains.plugins.scala.worksheet.settings.RunTypes.PlainRunType
import org.jetbrains.plugins.scala.worksheet.settings.{WorksheetCommonSettings, WorksheetFileSettings}

class RunWorksheetAction extends AnAction with TopComponentAction {

  def actionPerformed(e: AnActionEvent): Unit = {
    RunWorksheetAction.runCompiler(e.getProject, auto = false)
  }

  override def update(e: AnActionEvent): Unit = {
    super.update(e)
    
    val shortcuts = KeymapManager.getInstance.getActiveKeymap.getShortcuts("Scala.RunWorksheet")
    
    if (shortcuts.nonEmpty) {
      val shortcutText = " (" + KeymapUtil.getShortcutText(shortcuts(0)) + ")"
      e.getPresentation.setText(ScalaBundle.message("worksheet.execute.button") + shortcutText)
    }
  }

  override def actionIcon: Icon = AllIcons.Actions.Execute

  override def bundleKey: String = "worksheet.execute.button"

  override def shortcutId: Option[String] = Some("Scala.RunWorksheet")
}

object RunWorksheetAction {
  private val runnerClassName = "org.jetbrains.plugins.scala.worksheet.MyWorksheetRunner"

  def runCompiler(project: Project, auto: Boolean) {
    Stats.trigger(FeatureKey.runWorksheet)

    if (project == null) return

    val editor = FileEditorManager.getInstance(project).getSelectedTextEditor

    if (editor == null) return

    val psiFile: PsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument)
    WorksheetProcessManager.stop(psiFile.getVirtualFile)

    val file = psiFile match {
      case file: ScalaFile if file.isWorksheetFile => file
      case _ => return
    }

    val viewer = WorksheetCache.getInstance(project) getViewer editor

    if (viewer != null && !WorksheetFileSettings.isRepl(file)) {
      invokeAndWait(ModalityState.any()) {
        inWriteAction {
          CleanWorksheetAction.resetScrollModel(viewer)
          if (!auto) {
            CleanWorksheetAction.cleanWorksheet(file.getNode, editor, viewer, project)
          }
        }
      }
    }

    def runnable(): Unit = {
      val callback: (String, String) => Unit = (className: String, addToCp: String) =>
        invokeLater {
          executeWorksheet(file.getName, project, file.getContainingFile, className, addToCp)
        }
      val compiler = new WorksheetCompiler(editor, file, callback, auto)
      compiler.compileAndRunFile()
    }

    val fileSettings = WorksheetCommonSettings(file)

    if (fileSettings.isMakeBeforeRun) {
      val compilerNotification: CompileStatusNotification =
        (aborted: Boolean, errors: Int, _: Int, _: CompileContext) => {
          if (!aborted && errors == 0)
            runnable()
        }
      CompilerManager.getInstance(project).make(fileSettings.getModuleFor, compilerNotification)
    } else {
      runnable()
    }
  }

  //FYI: repl mode works only if we use compiler server and run worksheet with "InProcess" setting
  private def executeWorksheet(name: String, project: Project, file: PsiFile, mainClassName: String, addToCp: String) {
    val virtualFile = file.getVirtualFile
    //todo extract default java options??
    val params = createParameters(
      module = WorksheetCommonSettings(file).getModuleFor,
      mainClassName = mainClassName,
      workingDirectory = Option(project.baseDir).map(_.getPath).getOrElse(""),
      additionalCp = addToCp,
      consoleArgs = "",
      worksheetField = virtualFile.getCanonicalPath
    )

    setUpUiAndRun(params.createOSProcessHandler(), file)
  }

  private def createParameters(module: Module, mainClassName: String,
                               workingDirectory: String, additionalCp: String, consoleArgs: String, worksheetField: String) =  {
    if (module == null) throw new ExecutionException("Module is not specified")

    val project = module.getProject

    val rootManager = ModuleRootManager.getInstance(module)
    val sdk = rootManager.getSdk
    if (sdk == null || !sdk.getSdkType.isInstanceOf[JavaSdkType]) {
      throw CantRunException.noJdkForModule(module)
    }

    val params = new JavaParameters()

    params.getClassPath.addScalaClassPath(module)
    params.setUseDynamicClasspath(JdkUtil.useDynamicClasspath(project))
    params.getClassPath.addRunners()
    params.setWorkingDirectory(workingDirectory)
    params.setMainClass(runnerClassName)
    params.configureByModule(module, JavaParameters.JDK_AND_CLASSES_AND_TESTS)

    params.getClassPath.addRunners()
    params.getClassPath.add(additionalCp)
    params.getProgramParametersList.addParametersString(worksheetField)
    if (!consoleArgs.isEmpty)
      params.getProgramParametersList.addParametersString(consoleArgs)
    params.getProgramParametersList.prepend(mainClassName) //IMPORTANT! this must be first program argument

    params
  }

  private def setUpUiAndRun(handler: OSProcessHandler, file: PsiFile) {
    val editor = EditorHelper.openInEditor(file)
    
    val scalaFile = file match {
      case sc: ScalaFile => sc
      case _ => return 
    }
      
    val worksheetPrinter = PlainRunType.createPrinter(editor, scalaFile)
    if (worksheetPrinter == null) return 

    val myProcessListener: ProcessAdapter = new ProcessAdapter {
      override def onTextAvailable(event: ProcessEvent, outputType: Key[_]) {
        val text = event.getText
        if (ConsoleViewContentType.NORMAL_OUTPUT == ConsoleViewContentType.getConsoleViewType(outputType))
          worksheetPrinter.processLine(text)
      }

      override def processTerminated(event: ProcessEvent): Unit = {
        worksheetPrinter.flushBuffer()
      }
    }

    handler.addProcessListener(myProcessListener)
    handler.startNotify()
  }
}