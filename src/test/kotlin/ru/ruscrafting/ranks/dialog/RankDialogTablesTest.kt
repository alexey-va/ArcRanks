package ru.ruscrafting.ranks.dialog

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.net.URLClassLoader

class RankDialogTablesTest : FunSpec({
    test("table bridge adapts pairs to the renderer plugin classloader") {
        val ownerName = IsolatedTableOwner::class.java.name
        val urls = arrayOf(IsolatedTableOwner::class.java.protectionDomain.codeSource.location,
            Pair::class.java.protectionDomain.codeSource.location)
        val loader = object : URLClassLoader(urls, IsolatedTableOwner::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                val isolated = name == "kotlin.Pair" || name.startsWith(ownerName)
                val result = findLoadedClass(name) ?: if (isolated) findClass(name) else super.loadClass(name, false)
                if (resolve) resolveClass(result)
                result
            }
        }
        loader.use {
            val owner = loader.loadClass(ownerName)
            val frame = loader.loadClass("$ownerName\$Frame")
            val columns = loader.loadClass("$ownerName\$Columns")
            shouldThrow<NoSuchMethodException> {
                owner.getMethod("render", List::class.java, Pair::class.java, frame, Int::class.javaPrimitiveType, columns)
            }
            val rendered = RankDialogTables.bind(owner)(listOf(Component.text("Bread") to Component.text("100")), RankDialogTables.Frame.LEGENDARY, 468)
            PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe "Bread100"
        }
    }
})

/** Owns a separate Kotlin Pair just as independently shaded plugins can. */
object IsolatedTableOwner {
    enum class Frame { EPIC, LEGENDARY }
    enum class Columns { AUTO }
    data class Result(val component: Component)
    @Suppress("UNUSED_PARAMETER")
    fun render(rows: List<Pair<Component, Component>>, headers: Pair<Component, Component>?, frame: Frame, width: Int, columns: Columns): Result =
        Result(rows.fold(Component.empty() as Component) { result, row -> result.append(row.first).append(row.second) })
}
