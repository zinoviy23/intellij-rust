/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.import

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.util.AutoInjectedCrates
import org.rust.ide.search.RsCargoProjectScope
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.TraitImplSource
import org.rust.lang.core.resolve.ref.MethodResolveVariant
import org.rust.lang.core.resolve.ref.deepResolve
import org.rust.lang.core.stubs.index.RsNamedElementIndex
import org.rust.lang.core.stubs.index.RsReexportIndex
import org.rust.lang.core.types.inference
import org.rust.lang.core.types.ty.TyPrimitive
import org.rust.openapiext.Testmark
import org.rust.openapiext.checkWriteAccessAllowed
import org.rust.openapiext.runWriteCommandAction

class AutoImportFix(element: RsElement) : LocalQuickFixOnPsiElement(element), HighPriorityAction {

    private var isConsumed: Boolean = false

    override fun getFamilyName(): String = NAME
    override fun getText(): String = familyName

    public override fun isAvailable(): Boolean = super.isAvailable() && !isConsumed

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        invoke(project)
    }

    fun invoke(project: Project) {
        val element = startElement as? RsElement ?: return
        val (_, candidates) = when (element) {
            is RsPath -> findApplicableContext(project, element) ?: return
            is RsMethodCall -> findApplicableContext(project, element)  ?: return
            else -> return
        }

        if (candidates.size == 1) {
            element.containingMod.importItem(candidates.first())
        } else {
            DataManager.getInstance().dataContextFromFocusAsync.onSuccess {
                chooseItemAndImport(project, it, candidates, element.containingMod)
            }
        }
        isConsumed = true
    }

    private fun chooseItemAndImport(
        project: Project,
        dataContext: DataContext,
        items: List<ImportCandidate>,
        originalElement: RsElement
    ) {
        showItemsToImportChooser(project, dataContext, items) { selectedValue ->
            project.runWriteCommandAction {
                originalElement.containingMod.importItem(selectedValue)
            }
        }
    }

    companion object {

        const val NAME = "Import"

        fun findApplicableContext(project: Project, path: RsPath): Context<RsPath>? {
            val basePath = path.basePath()
            if (TyPrimitive.fromPath(basePath) != null) return null
            if (basePath.reference.multiResolve().isNotEmpty()) return null

            // Don't try to import path in use item
            if (path.ancestorStrict<RsUseSpeck>() != null) {
                Testmarks.pathInUseItem.hit()
                return Context(basePath, emptyList())
            }

            val candidates = getImportCandidates(ImportContext.from(project, path), basePath.referenceName, path.text) {
                path != basePath || !(it.item is RsMod || it.item is RsModDeclItem || it.item.parent is RsMembers)
            }.toList()

            return Context(basePath, candidates)
        }

        fun findApplicableContext(project: Project, methodCall: RsMethodCall): Context<Unit>? {
            val results = methodCall.inference?.getResolvedMethod(methodCall) ?: emptyList()
            if (results.isEmpty()) return Context(Unit, emptyList())

            val traitsToImport = collectTraitsToImport(methodCall, results) ?: return null

            val superMods = LinkedHashSet(methodCall.containingMod.superMods)
            val attributes = methodCall.stdlibAttributes

            val candidates = traitsToImport
                .asSequence()
                .flatMap { QualifiedNamedItem.ExplicitItem(it).withModuleReexports(project).asSequence() }
                .mapNotNull { importItem -> importItem.toImportCandidate(superMods) }
                .filterImportCandidates(attributes)
                .toList()

            return Context(Unit, candidates)
        }

        /**
         * Returns a sequence of import candidates, after importing any of which it becomes possible to resolve the
         * path created from the `importingPathText`.
         *
         * @param importContext    The information about a path for which import candidates are looked for.
         * @param targetName        The name of searched import candidate.
         * @param importingPathText The text of the path that must resolve to candidates after import.
         * @param itemFilter        Additional filter for items before they become candidates.
         * @return the sequence of import candidates.
         */
        fun getImportCandidates(
            importContext: ImportContext,
            targetName: String,
            importingPathText: String,
            itemFilter: (QualifiedNamedItem) -> Boolean
        ): Sequence<ImportCandidate> {
            val project = importContext.project

            val explicitItems = RsNamedElementIndex.findElementsByName(project, targetName, importContext.scope)
                .asSequence()
                .filterIsInstance<RsQualifiedNamedElement>()
                .map { QualifiedNamedItem.ExplicitItem(it) }

            val reexportedItems = RsReexportIndex.findReexportsByName(project, targetName, importContext.scope)
                .asSequence()
                .mapNotNull {
                    val item = it.path?.reference?.resolve() as? RsQualifiedNamedElement ?: return@mapNotNull null
                    QualifiedNamedItem.ReexportedItem(it, item)
                }

            return (explicitItems + reexportedItems)
                .filter(itemFilter)
                .flatMap { it.withModuleReexports(project).asSequence() }
                .mapNotNull { it.toImportCandidate(importContext.superMods) }
                .filterImportCandidates(importContext.attributes)
                // check that result after import can be resolved and resolved element is suitable
                // if no, don't add it in candidate list
                .filter { canBeResolvedToSuitableItem(importingPathText, importContext, it.info) }
        }

        private fun QualifiedNamedItem.toImportCandidate(superMods: LinkedHashSet<RsMod>): ImportCandidate? =
            canBeImported(superMods)?.let { ImportCandidate(this, it) }

        private fun collectTraitsToImport(
            methodCall: RsMethodCall,
            resolveResults: List<MethodResolveVariant>
        ): List<RsTraitItem>? {
            val traits = resolveResults.mapNotNull { variant ->
                val source = variant.source
                val trait = when (source) {
                    is TraitImplSource.ExplicitImpl -> {
                        val impl = source.value
                        if (impl.traitRef == null) return null
                        impl.traitRef?.resolveToTrait ?: return@mapNotNull null
                    }
                    is TraitImplSource.Derived -> source.value
                    is TraitImplSource.Collapsed -> source.value
                    is TraitImplSource.Hardcoded -> source.value

                    is TraitImplSource.TraitBound -> return null
                    is TraitImplSource.Object -> return null
                }

                trait
            }
            return if (traits.filterInScope(methodCall).isNotEmpty()) null else traits
        }

        // Semantic signature of method is `ImportItem.canBeImported(mod: RsMod)`
        // but in our case `mod` is always same and `mod` needs only to get set of its super mods
        // so we pass `superMods` instead of `mod` for optimization
        private fun QualifiedNamedItem.canBeImported(superMods: LinkedHashSet<RsMod>): ImportInfo? {
            if (item !is RsVisible) return null

            val ourSuperMods = this.superMods ?: return null
            val parentMod = ourSuperMods.getOrNull(0) ?: return null

            // try to find latest common ancestor module of `parentMod` and `mod` in module tree
            // we need to do it because we can use direct child items of any super mod with any visibility
            val lca = ourSuperMods.find { it in superMods }
            val crateRelativePath = crateRelativePath ?: return null

            val (shouldBePublicMods, importInfo) = if (lca == null) {
                if (!isPublic) return null
                val target = containingCargoTarget ?: return null
                val externCrateMod = ourSuperMods.last()

                val externCrateWithDepth = superMods.withIndex().mapNotNull { (index, superMod) ->
                    val externCrateItem = superMod.childrenOfType<RsExternCrateItem>()
                        .find { it.reference.resolve() == externCrateMod } ?: return@mapNotNull null
                    val depth = if (superMod.isCrateRoot) null else index
                    externCrateItem to depth
                }.singleOrNull()

                val (externCrateName, needInsertExternCrateItem, depth) = if (externCrateWithDepth == null) {
                    Triple(target.normName, true, null)
                } else {
                    val (externCrateItem, depth) = externCrateWithDepth
                    Triple(externCrateItem.nameWithAlias, false, depth)
                }

                val importInfo = ImportInfo.ExternCrateImportInfo(target, externCrateName,
                    needInsertExternCrateItem, depth, crateRelativePath)
                ourSuperMods to importInfo
            } else {
                // if current item is direct child of some ancestor of `mod` then it can be not public
                if (parentMod == lca) return ImportInfo.LocalImportInfo(crateRelativePath)
                if (!isPublic) return null
                ourSuperMods.takeWhile { it != lca }.dropLast(1) to ImportInfo.LocalImportInfo(crateRelativePath)
            }
            return if (shouldBePublicMods.all { it.isPublic }) return importInfo else null
        }

        private fun canBeResolvedToSuitableItem(
            importingPathName: String,
            context: ImportContext,
            info: ImportInfo
        ): Boolean {
            val externCrateName = if (info !is ImportInfo.ExternCrateImportInfo ||
                context.attributes == RsFile.Attributes.NONE && info.target.isStd ||
                context.attributes == RsFile.Attributes.NO_STD && info.target.isCore) {
                null
            } else {
                info.externCrateName
            }
            val path = RsCodeFragmentFactory(context.project)
                .createPathInTmpMod(importingPathName, context.mod, context.ns, info.usePath, externCrateName) ?: return false
            val element = path.reference.deepResolve() as? RsQualifiedNamedElement ?: return false
            if (!context.namespaceFilter(element)) return false
            return !(element.parent is RsMembers && element.ancestorStrict<RsTraitItem>() != null)
        }

        private fun Sequence<ImportCandidate>.filterImportCandidates(
            attributes: RsFile.Attributes
        ): Sequence<ImportCandidate> = groupBy { it.qualifiedNamedItem.item }
            .map { (_, candidates) -> candidates }
            .asSequence()
            .flatMap { candidates -> filterForSingleItem(candidates, attributes).asSequence() }

        private fun filterForSingleItem(
            candidates: List<ImportCandidate>,
            fileAttributes: RsFile.Attributes
        ): List<ImportCandidate> {
            val candidatesWithPackage = mutableListOf<Pair<ImportCandidate, CargoWorkspace.Package>>()

            val stdlibCandidates = mutableListOf<Pair<ImportCandidate, CargoWorkspace.Package>>()

            for (candidate in candidates) {
                val pkg = candidate.qualifiedNamedItem.containingCargoTarget?.pkg ?: continue
                val container = if (pkg.origin == PackageOrigin.STDLIB) stdlibCandidates else candidatesWithPackage
                container += candidate to pkg
            }

            candidatesWithPackage += filterStdlibCandidates(stdlibCandidates, fileAttributes)
            val pkgToCandidates = candidatesWithPackage.groupBy({ (_, pkg) -> pkg }, { (candidate, _) -> candidate })
            return pkgToCandidates.flatMap { (_, candidates) -> filterInPackage(candidates) }
        }

        private fun filterStdlibCandidates(
            stdlibCandidates: List<Pair<ImportCandidate, CargoWorkspace.Package>>,
            fileAttributes: RsFile.Attributes
        ): List<Pair<ImportCandidate, CargoWorkspace.Package>> {
            var hasImportWithSameAttributes = false
            val candidateToAttributes = stdlibCandidates.map { candidate ->
                val pkg = candidate.second
                val attributes = when (pkg.normName) {
                    AutoInjectedCrates.STD -> RsFile.Attributes.NONE
                    AutoInjectedCrates.CORE -> RsFile.Attributes.NO_STD
                    else -> RsFile.Attributes.NO_CORE
                }
                hasImportWithSameAttributes = hasImportWithSameAttributes || attributes == fileAttributes
                candidate to attributes
            }

            val condition: (RsFile.Attributes) -> Boolean = if (hasImportWithSameAttributes) {
                attributes -> attributes == fileAttributes
            } else {
                attributes -> attributes < fileAttributes
            }
            return candidateToAttributes.mapNotNull { (candidate, attributes) ->
                if (condition(attributes)) candidate else null
            }
        }

        private fun filterInPackage(candidates: List<ImportCandidate>): List<ImportCandidate> {
            val (simpleImports, compositeImports) = candidates.partition {
                it.qualifiedNamedItem !is QualifiedNamedItem.CompositeItem
            }

            // If there is item reexport from some parent module of current import path
            // we want to drop this import candidate
            //
            // For example, in the following case
            //
            // mod_a -- mod_b -- Item
            //   \_______________/
            //
            // we have `mod_a::mod_b::Item` and `mod_a::Item` import candidates.
            // `mod_a::Item` is reexport of `Item` so we don't want to add `mod_a::mod_b::Item`
            // into final import list
            val candidatesWithSuperMods = simpleImports.mapNotNull {
                val superMods = it.qualifiedNamedItem.superMods ?: return@mapNotNull null
                it to superMods
            }
            val parents = candidatesWithSuperMods.mapTo(HashSet()) { (_, superMods) -> superMods[0] }
            val filteredSimpleImports = candidatesWithSuperMods.mapNotNull { (candidate, superMods) ->
                if (superMods.asSequence().drop(1).none { it in parents }) candidate else null
            }
            return filteredSimpleImports + compositeImports
        }
    }

    data class Context<T>(
        val data: T,
        val candidates: List<ImportCandidate>
    )

    object Testmarks {
        val autoInjectedStdCrate = Testmark("autoInjectedStdCrate")
        val autoInjectedCoreCrate = Testmark("autoInjectedCoreCrate")
        val pathInUseItem = Testmark("pathInUseItem")
        val externCrateItemInNotCrateRoot = Testmark("externCrateItemInNotCrateRoot")
    }
}

sealed class ImportInfo {

    abstract val usePath: String

    class LocalImportInfo(override val usePath: String): ImportInfo()

    class ExternCrateImportInfo(
        val target: CargoWorkspace.Target,
        val externCrateName: String,
        val needInsertExternCrateItem: Boolean,
        /**
         * Relative depth of importing path's module to module with extern crate item.
         * Used for creation of relative use path.
         *
         * For example, in the following case
         * ```rust
         * // lib.rs from bar crate
         * pub struct Bar {}
         * ```
         *
         * ```rust
         * // main.rs from our crate
         * mod foo {
         *     extern crate bar;
         *     mod baz {
         *          fn f(bar: Bar/*caret*/) {}
         *     }
         * }
         * ```
         *
         * relative depth of path `Bar` is `1`, so we should add `self::` prefix to use path.
         *
         * Can be null if extern crate item is absent or it is in crate root.
         */
        val depth: Int?,
        crateRelativePath: String
    ) : ImportInfo() {
        override val usePath: String = "$externCrateName::$crateRelativePath"
    }
}

data class ImportCandidate(val qualifiedNamedItem: QualifiedNamedItem, val info: ImportInfo)

private val RsPath.namespaceFilter: (RsQualifiedNamedElement) -> Boolean get() = when (context) {
    is RsTypeElement -> { e ->
        when (e) {
            is RsEnumItem,
            is RsStructItem,
            is RsTraitItem,
            is RsTypeAlias -> true
            else -> false
        }
    }
    is RsPathExpr -> { e ->
        when (e) {
            // TODO: take into account fields type
            is RsFieldsOwner,
            is RsConstant,
            is RsFunction -> true
            else -> false
        }
    }
    is RsTraitRef -> { e -> e is RsTraitItem }
    is RsStructLiteral -> { e -> e is RsFieldsOwner && e.blockFields != null }
    is RsPatBinding -> { e ->
        when (e) {
            is RsEnumItem,
            is RsEnumVariant,
            is RsStructItem,
            is RsTypeAlias,
            is RsConstant,
            is RsFunction -> true
            else -> false
        }
    }
    else -> { _ -> true }
}

/**
 * Inserts an use declaration to the receiver mod for importing the selected `candidate`.
 * This action requires write access.
 */
fun RsMod.importItem(candidate: ImportCandidate) {
    checkWriteAccessAllowed()
    val psiFactory = RsPsiFactory(project)
    // depth of `mod` relative to module with `extern crate` item
    // we uses this info to create correct relative use item path if needed
    var relativeDepth: Int? = null

    val isEdition2018 = isEdition2018
    val info = candidate.info
    // if crate of importing element differs from current crate
    // we need to add new extern crate item
    if (info is ImportInfo.ExternCrateImportInfo) {
        val target = info.target
        val crateRoot = crateRoot
        val attributes = crateRoot?.stdlibAttributes ?: RsFile.Attributes.NONE
        when {
            // but if crate of imported element is `std` and there aren't `#![no_std]` and `#![no_core]`
            // we don't add corresponding extern crate item manually
            // because it will be done by compiler implicitly
            attributes == RsFile.Attributes.NONE && target.isStd -> AutoImportFix.Testmarks.autoInjectedStdCrate.hit()
            // if crate of imported element is `core` and there is `#![no_std]`
            // we don't add corresponding extern crate item manually for the same reason
            attributes == RsFile.Attributes.NO_STD && target.isCore -> AutoImportFix.Testmarks.autoInjectedCoreCrate.hit()
            else -> {
                if (info.needInsertExternCrateItem && !isEdition2018) {
                    crateRoot?.insertExternCrateItem(psiFactory, info.externCrateName)
                } else {
                    if (info.depth != null) {
                        AutoImportFix.Testmarks.externCrateItemInNotCrateRoot.hit()
                        relativeDepth = info.depth
                    }
                }
            }
        }
    }
    val prefix = when (relativeDepth) {
        null -> if (info is ImportInfo.LocalImportInfo && isEdition2018) "crate::" else ""
        0 -> "self::"
        else -> "super::".repeat(relativeDepth)
    }
    insertUseItem(psiFactory, "$prefix${info.usePath}")
}

private fun RsMod.insertExternCrateItem(psiFactory: RsPsiFactory, crateName: String) {
    val externCrateItem = psiFactory.createExternCrateItem(crateName)
    val lastExternCrateItem = childrenOfType<RsExternCrateItem>().lastElement
    if (lastExternCrateItem != null) {
        addAfter(externCrateItem, lastExternCrateItem)
    } else {
        addBefore(externCrateItem, firstItem)
        addAfter(psiFactory.createNewline(), firstItem)
    }
}

private fun RsMod.insertUseItem(psiFactory: RsPsiFactory, usePath: String) {
    val useItem = psiFactory.createUseItem(usePath)
    val anchor = childrenOfType<RsUseItem>().lastElement ?: childrenOfType<RsExternCrateItem>().lastElement
    if (anchor != null) {
        val insertedUseItem = addAfter(useItem, anchor)
        if (anchor is RsExternCrateItem) {
            addBefore(psiFactory.createNewline(), insertedUseItem)
        }
    } else {
        addBefore(useItem, firstItem)
        addAfter(psiFactory.createNewline(), firstItem)
    }
}

@Suppress("DataClassPrivateConstructor")
data class ImportContext private constructor(
    val project: Project,
    val mod: RsMod,
    val superMods: LinkedHashSet<RsMod>,
    val scope: GlobalSearchScope,
    val ns: RsPsiFactory.PathNamespace,
    val attributes: RsFile.Attributes,
    val namespaceFilter: (RsQualifiedNamedElement) -> Boolean
) {
    companion object {
        fun from(project: Project, path: RsPath): ImportContext = ImportContext(
            project = project,
            mod = path.containingMod,
            superMods = LinkedHashSet(path.containingMod.superMods),
            scope = RsCargoProjectScope(project.cargoProjects, GlobalSearchScope.allScope(project)),
            ns = path.pathNamespace,
            attributes = path.stdlibAttributes,
            namespaceFilter = path.namespaceFilter
        )
    }
}

private val RsPath.pathNamespace: RsPsiFactory.PathNamespace get() = when (context) {
    is RsPathExpr,
    is RsStructLiteral -> RsPsiFactory.PathNamespace.VALUES
    else -> RsPsiFactory.PathNamespace.TYPES
}
private val RsElement.stdlibAttributes: RsFile.Attributes
    get() = (crateRoot?.containingFile as? RsFile)?.attributes ?: RsFile.Attributes.NONE
private val RsItemsOwner.firstItem: RsElement get() = itemsAndMacros.first { it !is RsAttr }
private val <T: RsElement> List<T>.lastElement: T? get() = maxBy { it.textOffset }

private val CargoWorkspace.Target.isStd: Boolean
    get() = pkg.origin == PackageOrigin.STDLIB && normName == AutoInjectedCrates.STD

private val CargoWorkspace.Target.isCore: Boolean
    get() = pkg.origin == PackageOrigin.STDLIB && normName == AutoInjectedCrates.CORE
