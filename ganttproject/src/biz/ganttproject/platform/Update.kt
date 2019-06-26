/*
Copyright 2019 BarD Software s.r.o

This file is part of GanttProject, an open-source project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package biz.ganttproject.platform

import biz.ganttproject.app.*
import biz.ganttproject.core.option.DefaultStringOption
import biz.ganttproject.core.option.GPOptionGroup
import biz.ganttproject.lib.fx.VBoxBuilder
import com.bardsoftware.eclipsito.update.UpdateMetadata
import com.google.common.base.Strings
import com.sandec.mdfx.MDFXNode
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.event.ActionEvent
import javafx.scene.Node
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.sourceforge.ganttproject.GPLogger
import net.sourceforge.ganttproject.gui.UIFacade
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities
import org.eclipse.core.runtime.Platform as Eclipsito


fun showUpdateDialog(updates: List<UpdateMetadata>, uiFacade: UIFacade, showSkipped: Boolean = false) {
  val latestShownUpdateMetadata = UpdateMetadata(UpdateOptions.latestShownVersion.value, null, null, null, 0)
  val filteredUpdates = updates
      .filter { showSkipped || Strings.nullToEmpty(latestShownUpdateMetadata.version).isEmpty() || it > latestShownUpdateMetadata }
  if (filteredUpdates.isNotEmpty()) {
    val dlg = UpdateDialog(filteredUpdates, uiFacade)
    dialog(dlg::addContent)
  }
}

/**
 * @author dbarashev@bardsoftware.com
 */
private class UpdateDialog(private val updates: List<UpdateMetadata>, private val uiFacade: UIFacade) {
  private lateinit var dialogApi: DialogController
  private val version2ui = mutableMapOf<String, UpdateComponentUi>()

  fun createPane(): Node {
    val bodyBuilder = VBoxBuilder()
    this.updates
        .map {
          UpdateComponentUi(it).also { ui ->
            version2ui[it.version] = ui
          }
        }.forEach {
          bodyBuilder.add(it.title)
          bodyBuilder.add(it.subtitle)
          bodyBuilder.add(it.text)
          bodyBuilder.add(it.progress)
        }
    return ScrollPane(bodyBuilder.vbox).also {
      it.styleClass.add("body")
      it.isFitToWidth = true
      it.hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
      it.vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
    }
  }

  fun addContent(dialogApi: DialogController) {
    this.dialogApi = dialogApi
    dialogApi.addStyleClass("dlg-platform-update")
    dialogApi.addStyleSheet(
        "/biz/ganttproject/platform/Update.css",
        "/biz/ganttproject/storage/StorageDialog.css",
        "/biz/ganttproject/storage/cloud/GPCloudStorage.css")

    val vboxBuilder = VBoxBuilder()
    vboxBuilder.addTitle(i18n.formatText("title"))
    vboxBuilder.add(Label().apply {
      this.text = i18n.formatText("titleHelp", this@UpdateDialog.updates.first().version)
      this.styleClass.add("help")
    })

    val downloadCompleted = SimpleBooleanProperty(false)
    dialogApi.setHeader(vboxBuilder.vbox)
    dialogApi.setupButton(ButtonType.APPLY) { btn ->
      ButtonBar.setButtonUniformSize(btn, false)
      btn.styleClass.add("btn-attention")
      btn.text = i18n.formatText("button.ok")
      btn.maxWidth = Double.MAX_VALUE
      btn.addEventFilter(ActionEvent.ACTION) { event ->
        if (btn.properties["restart"] == true) {
          onRestart()
        } else {
          event.consume()
          btn.disableProperty().set(true)
          onDownload(downloadCompleted)
        }
      }
      downloadCompleted.addListener { _, _, newValue ->
        if (newValue) {
          btn.disableProperty().set(false)
          btn.text = i18n.formatText("restart")
          btn.properties["restart"] = true
        }
      }
    }
    dialogApi.setupButton(ButtonType.CLOSE) { btn ->
      btn.styleClass.add("btn")
      btn.text = i18n.formatText("button.close_skip")
      btn.addEventFilter(ActionEvent.ACTION) {
        UpdateOptions.latestShownVersion.value = this.updates.first().version
      }
      downloadCompleted.addListener { _, _, newValue ->
        if (newValue) {
          btn.text = i18n.formatText("close")
        }
      }
    }
    dialogApi.setContent(this.createPane())
  }

  private fun onRestart() {
    SwingUtilities.invokeLater {
      uiFacade.mainFrame.dispatchEvent(WindowEvent(uiFacade.mainFrame, WindowEvent.WINDOW_CLOSING))
    }
  }

  private fun onDownload(completed: SimpleBooleanProperty) {
    var installFuture: CompletableFuture<File>? = null
    for (update in updates.reversed()) {
      val progressMonitor: (Int) -> Unit = { percents: Int ->
        this.version2ui[update.version]?.updateProgress(percents)
      }
      installFuture =
          if (installFuture == null) update.install(progressMonitor)
          else installFuture.thenCompose { update.install(progressMonitor) }
    }
    installFuture?.thenAccept {
      GlobalScope.launch(Dispatchers.Main) {
        completed.value = true
      }
    }?.exceptionally { ex ->
      GPLogger.logToLogger(ex)
      this.dialogApi.showAlert(i18n.create("alert.title"), createAlertBody(ex.message ?: ""))
      null
    }
  }
}

private fun (UpdateMetadata).install(monitor: (Int) -> Unit): CompletableFuture<File> {
  return Eclipsito.getUpdater().installUpdate(this) { percents ->
    monitor(percents)
  }
}

private fun (UpdateMetadata).sizeAsString(): String {
  return when {
    this.sizeBytes < (1 shl 10) -> """${this.sizeBytes}b"""
    this.sizeBytes >= (1 shl 10) && this.sizeBytes < (1 shl 20) -> """${this.sizeBytes / (1 shl 10)}KiB"""
    else -> "%.2fMiB".format(this.sizeBytes.toFloat() / (1 shl 20))
  }
}

private class UpdateComponentUi(val update: UpdateMetadata) {
  val title: Label
  val subtitle: Label
  val text: MDFXNode
  val progressText = i18n.create("bodyItem.progress")
  val progress: Label
  var progressValue: Int = -1

  init {
    title = Label(i18n.formatText("bodyItem.title", update.version)).also { l ->
      l.styleClass.add("title")
    }
    subtitle = Label(i18n.formatText("bodyItem.subtitle", update.date, update.sizeAsString())).also { l ->
      l.styleClass.add("subtitle")
    }
    text = MDFXNode(i18n.formatText("bodyItem.description", update.description)).also { l ->
      l.styleClass.add("par")
    }
    progress = Label().also {
      it.textProperty().bind(progressText)
      it.styleClass.add("progress")
      it.isVisible = false
    }
  }

  fun updateProgress(percents: Int) {
    Platform.runLater {
      if (progressValue == -1) {
        this.progress.isVisible = true
      }
      if (progressValue != percents) {
        progressValue = percents
        progressText.update(percents.toString())
        if (progressValue == 100) {
          listOf(title, subtitle, text, progress).forEach { it.opacity = 0.5 }
        }
      }
    }
  }
}

private val i18n = DefaultLocalizer("platform.update", RootLocalizer)

object UpdateOptions {
  val latestShownVersion = DefaultStringOption("latestShownVersion")
  val optionGroup: GPOptionGroup = GPOptionGroup("platform.update", UpdateOptions.latestShownVersion)

}