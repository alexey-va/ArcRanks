package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import ru.arc.paper.menu.PaperDialogBody

/** Optional bridge to ARC's pack-owned renderer. Only Adventure components cross the plugin boundary. */
internal object RankDialogTables {
    enum class Frame { EPIC, LEGENDARY }

    private val renderer by lazy {
        try {
            val owner = Class.forName("ru.arc.gui.DialogTables")
            val frame = Class.forName("ru.arc.gui.DialogTables\$Frame")
            val columns = Class.forName("ru.arc.gui.DialogTables\$Columns")
            val method = owner.getMethod("render", List::class.java, Pair::class.java, frame, Int::class.javaPrimitiveType, columns)
            val instance = owner.getField("INSTANCE").get(null)
            val auto = columns.enumConstants.first { (it as Enum<*>).name == "AUTO" }
            val component = Class.forName("ru.arc.gui.DialogTables\$Result").getMethod("getComponent")
            val render: (List<Pair<Component, Component>>, Frame, Int) -> Component = { rows, style, width ->
                val selected = frame.enumConstants.first { (it as Enum<*>).name == style.name }
                component.invoke(method.invoke(instance, rows, null, selected, width, auto)) as Component
            }
            render
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    fun body(rows: List<Pair<Component, Component>>, frame: Frame = Frame.EPIC, width: Int = 468): PaperDialogBody {
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
