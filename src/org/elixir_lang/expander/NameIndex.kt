package org.elixir_lang.expander

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.containers.CollectionFactory
import org.elixir_lang.NameArity
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.expander.ExpanderSource.Probe
import java.util.TreeMap
import org.elixir_lang.beam.psi.Module as BeamModule

/** [T]s by name, held in name order. */
internal class NameIndex<T> private constructor(byName: Map<String, List<T>>) {
    private val byName: Map<String, List<T>> = Examined(byName)

    /** [name]'s, or every name's when [name] is `null`. */
    fun named(name: String?): List<T> = if (name == null) byName.values.flatten() else byName[name].orEmpty()

    /** [map], counting [Probe.Counter.EXAMINED] for each [T] a read yields, however the read goes. */
    private class Examined<T>(private val map: Map<String, List<T>>) : AbstractMap<String, List<T>>() {
        override val entries: Set<Map.Entry<String, List<T>>> =
            object : AbstractSet<Map.Entry<String, List<T>>>() {
                override val size: Int get() = map.size

                override fun iterator(): Iterator<Map.Entry<String, List<T>>> {
                    val iterator = map.entries.iterator()

                    return object : Iterator<Map.Entry<String, List<T>>> {
                        override fun hasNext(): Boolean = iterator.hasNext()

                        override fun next(): Map.Entry<String, List<T>> = iterator.next().also { examined(it.value) }
                    }
                }
            }

        override fun get(key: String): List<T>? = map[key]?.also(::examined)

        override fun containsKey(key: String): Boolean = map.containsKey(key)

        private fun examined(items: List<T>) = Probe.count(Probe.Counter.EXAMINED, items.size)
    }

    companion object {
        private val tables =
            CollectionFactory.createConcurrentWeakIdentityMap<DefinitionTable, NameIndex<Map.Entry<NameArity, DefinitionTable.Entry>>>()
        private val imports = CollectionFactory.createConcurrentWeakIdentityMap<Env.Imports, NameIndex<NameArity>>()
        private val exports = CollectionFactory.createConcurrentWeakIdentityMap<ModuleExports.Present, NameIndex<NameArity>>()
        private val definitions = Key.create<CachedValue<NameIndex<CallDefinition>>>("org.elixir_lang.expander.NameIndex.definitions")

        /**
         * [table]'s named definitions, leaving out an entry a definition's defaults added while a definition of its name
         * still answers its arity through defaults. One overridden without them leaves it the only entry at that arity.
         */
        fun of(table: DefinitionTable): NameIndex<Map.Entry<NameArity, DefinitionTable.Entry>> =
            tables.computeIfAbsent(table) {
                val throughDefaults = it.entries.flatMapTo(HashSet()) { (nameArity, entry) ->
                    DefinitionTable.defaultArities(nameArity.arity, entry.defaults).map { arity -> NameArity(nameArity.name, arity) }
                }

                of(it.entries.entries.filterNot { (nameArity, entry) -> entry.default && nameArity in throughDefaults }) { (nameArity, _) ->
                    nameArity.name
                }
            }

        fun of(imports: Env.Imports): NameIndex<NameArity> =
            this.imports.computeIfAbsent(imports) { of(it.nameArities, NameArity::name) }

        /** [exports]' functions, as a remote call reaches them, and macros. */
        fun of(exports: ModuleExports.Present): NameIndex<NameArity> =
            this.exports.computeIfAbsent(exports) { of(it.exported + it.macros, NameArity::name) }

        /** [module]'s decompiled definitions, held on the module: they reference it, so a weak map would never drop it. */
        fun of(module: BeamModule): NameIndex<CallDefinition> =
            CachedValuesManager.getManager(module.project).getCachedValue(module, definitions, {
                CachedValueProvider.Result.create(
                    of(module.callDefinitions().toList()) { it.nameArityInterval.name },
                    ModificationTracker.NEVER_CHANGED,
                )
            }, false)

        private fun <T> of(items: Collection<T>, name: (T) -> String): NameIndex<T> {
            Probe.count(Probe.Counter.INDEXED, items.size)
            val byName = TreeMap<String, MutableList<T>>()
            for (item in items) byName.getOrPut(name(item)) { mutableListOf() }.add(item)

            return NameIndex(byName)
        }
    }
}
