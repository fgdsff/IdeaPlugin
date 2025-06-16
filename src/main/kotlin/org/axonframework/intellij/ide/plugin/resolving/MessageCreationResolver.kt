/*
 *  Copyright (c) 2022. Axon Framework
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.axonframework.intellij.ide.plugin.resolving

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.CachedValue
import org.axonframework.intellij.ide.plugin.api.MessageCreator
import org.axonframework.intellij.ide.plugin.resolving.creators.DefaultMessageCreator
import org.axonframework.intellij.ide.plugin.util.axonScope
import org.axonframework.intellij.ide.plugin.util.createCachedValue
import org.axonframework.intellij.ide.plugin.util.findParentHandlers
import org.axonframework.intellij.ide.plugin.util.javaFacade
import java.util.concurrent.ConcurrentHashMap

/**
 * Searches the codebase for places where a message payload is constructed.
 * It does this by searching for constructor references of compatible payloads. Inheritance is supported.
 *
 * Results are cached based on the Psi modifications of IntelliJ. This means the calculations are invalidated when
 * the PSI is modified (code is edited) or is collected by the garbage collector.
 */
class MessageCreationResolver(private val project: Project) {
    private val psiFacade = project.javaFacade()
    private val constructorsByPayloadCache = ConcurrentHashMap<String, CachedValue<List<MessageCreator>>>()

        /**
     * Retrieves all MessageCreator instances for a given payload. Will cache results, so don't worry about
     * calling it multiple times.
     *
     * @param payload qualified name of the payload
     * @return all message creators for the given payload
     */
    fun getCreatorsForPayload(payload: String): List<MessageCreator> {
        val cache = constructorsByPayloadCache.getOrPut(payload) {
            project.createCachedValue {
                findByPayload(payload)
            }
        }
        return cache.value
    }

    private fun findByPayload(payload: String): List<MessageCreator> {
        val scope = project.axonScope()
        val psiFacade = JavaPsiFacade.getInstance(project)

        val clazz = psiFacade.findClass(payload, scope) ?: return emptyList()
        val classes = listOf(clazz) + ClassInheritorsSearch.search(clazz, scope, true)

        val referenceCreators = classes
            .flatMap { cls ->
                val methods = cls.constructors + cls.methods.filter { it.name.contains("build", ignoreCase = true) }
                methods
                    .flatMap { MethodReferencesSearch.search(it, scope, true) }
                    .flatMap { ref -> createCreators(clazz.qualifiedName!!, ref.element) }
            }

        // Additional: detect usage as parameters of Spring boot endpoints
        val springParamCreators = mutableListOf<MessageCreator>()

        FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope).forEach { virtualFile ->
            if (!virtualFile.name.endsWith(".java")) return@forEach

            val fileText = virtualFile.contentsToByteArray().toString(Charsets.UTF_8)
            if (!clazz.name?.let { fileText.contains(it) }!!) return@forEach  // 💡 cheap string match

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@forEach

            psiFile.accept(object : JavaRecursiveElementWalkingVisitor() {
                override fun visitMethod(method: PsiMethod) {
                    method.parameterList.parameters.forEach { param ->
                        val type = param.type.canonicalText
                        if (type == payload || psiFacade.findClass(type, scope)?.isInheritor(clazz, true) == true) {
                            val hasSpringWebAnnotation = method.annotations.any {
                                val name = it.qualifiedName ?: return@any false
                                name.endsWith("GetMapping") || name.endsWith("PostMapping") || name.endsWith("PutMapping") ||
                                        name.endsWith("DeleteMapping") || name.endsWith("PatchMapping") || name.endsWith("RequestMapping")
                            }
                            if (hasSpringWebAnnotation) {
                                springParamCreators += createCreators(payload, param)
                            }
                        }
                    }
                }
            })
        }


        return (referenceCreators + springParamCreators).distinct()
    }

    private fun createCreators(payload: String, element: PsiElement): List<MessageCreator> {
        val parentHandlers = element.findParentHandlers()
        if (parentHandlers.isEmpty()) {
            return listOf(DefaultMessageCreator(element, payload, null))
        }
        return parentHandlers.map { DefaultMessageCreator(element, payload, it) }
    }
}
