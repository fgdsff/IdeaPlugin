package org.axonframework.intellij.ide.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import java.io.File
import kotlinx.serialization.json.*
import org.axonframework.intellij.ide.plugin.util.creatorResolver
import org.axonframework.intellij.ide.plugin.util.handlerResolver

class ExportAxonMapAction : AnAction("Export Axon Map", "Export Axon command → handler/creator map", null) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val resultJson = buildExport(project)
        writeJsonFile(project, resultJson)
    }

    private fun buildExport(project: Project): JsonObject {
        val handlers = project.handlerResolver().findAllHandlers()
        val export = mutableMapOf<String, JsonObject>()

        for (handler in handlers) {
            val messageFqName = handler.payload
            val handlerInfo = handler.element.containingFile.virtualFile.path

            val creators = project.creatorResolver().getCreatorsForPayload(handler.payload)
            val creatorPaths = creators.mapNotNull {
                it.element?.containingFile?.virtualFile?.path
            }

            val messageJson = JsonObject(mapOf(
                "handlers" to JsonArray(listOf(JsonPrimitive(handlerInfo))),
                "creators" to JsonArray(creatorPaths.map { JsonPrimitive(it) })
            ))

            export[messageFqName] = messageJson
        }

        return JsonObject(export)
    }

    private fun writeJsonFile(project: Project, data: JsonObject) {
        val outFile = File(project.basePath, "axon_map.json")
        outFile.writeText(Json.encodeToString(JsonObject.serializer(), data))
    }
}
