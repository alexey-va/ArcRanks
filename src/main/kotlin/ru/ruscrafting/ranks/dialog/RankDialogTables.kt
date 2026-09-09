package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import ru.arc.paper.menu.PaperDialogBody

/** Optional bridge to ARC's pack-owned renderer. Only Adventure components cross the plugin boundary. */
internal object RankDialogTables {
    enum class Frame { EPIC, LEGENDARY }

    private val renderer by lazy {
        try {
            bind(Class.forName("ru.arc.gui.DialogTables"))
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    internal fun bind(owner: Class<*>): (List<Pair<Component, Component>>, Frame, Int) -> Component {
        val loader = owner.classLoader
        val frame = Class.forName("${owner.name}\$Frame", true, loader)
        val columns = Class.forName("${owner.name}\$Columns", true, loader)
        val pair = Class.forName("kotlin.Pair", true, loader)
        val makePair = pair.getConstructor(Any::class.java, Any::class.java)
        val method = owner.getMethod("render", List::class.java, pair, frame, Int::class.javaPrimitiveType, columns)
        val instance = owner.getField("INSTANCE").get(null)
        val balanced = columns.enumConstants.first { (it as Enum<*>).name == "BALANCED" }
        val component = Class.forName("${owner.name}\$Result", true, loader).getMethod("getComponent")
        return { rows, style, width ->
            val selected = frame.enumConstants.first { (it as Enum<*>).name == style.name }
            val ownedRows = rows.map { (label, value) -> makePair.newInstance(label, value) }
            component.invoke(method.invoke(instance, ownedRows, null, selected, width, balanced)) as Component
        }
    }

    fun body(rows: List<Pair<Component, Component>>, frame: Frame = Frame.EPIC, width: Int = 320): PaperDialogBody {
        val rendered = try {
            renderer?.invoke(rows, frame, width)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
        return PaperDialogBody(rendered ?: Component.join(JoinConfiguration.newlines(), rows.map { (label, value) ->
            label.append(Component.text(": ")).append(value)
        }), width)
    }
}
