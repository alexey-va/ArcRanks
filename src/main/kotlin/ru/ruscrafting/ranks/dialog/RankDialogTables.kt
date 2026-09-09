package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import ru.arc.paper.menu.PaperDialogBody

/** Optional bridge to ARC's pack-owned renderer. Only Adventure components cross the plugin boundary. */
internal object RankDialogTables {
    enum class Frame { EPIC, LEGENDARY }

    private fun renderer(rowSeparators: Boolean, valueWide: Boolean = false) = try {
        bind(Class.forName("ru.arc.gui.DialogTables"), rowSeparators, valueWide)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private val wideRenderer by lazy { renderer(false, true) }
    private val separatedWideRenderer by lazy { renderer(true, true) }

    private val separatedRenderer by lazy { renderer(true) }

    private val renderer by lazy { renderer(false) }

    internal fun bind(owner: Class<*>, rowSeparators: Boolean = false, valueWide: Boolean = false): (List<Pair<Component, Component>>, Frame, Int) -> Component {
        val loader = owner.classLoader
        val frame = Class.forName("${owner.name}\$Frame", true, loader)
        val columns = Class.forName("${owner.name}\$Columns", true, loader)
        val pair = Class.forName("kotlin.Pair", true, loader)
        val makePair = pair.getConstructor(Any::class.java, Any::class.java)
        val spec = if (rowSeparators) Class.forName("${owner.name}\$Spec", true, loader) else null
        val method = if (spec == null) owner.getMethod("render", List::class.java, pair, frame, Int::class.javaPrimitiveType, columns)
            else owner.getMethod("render", List::class.java, pair, frame, Int::class.javaPrimitiveType, columns, spec)
        val options = spec?.getConstructor(Boolean::class.javaPrimitiveType)?.newInstance(true)
        val instance = owner.getField("INSTANCE").get(null)
        val balanced = columns.enumConstants.first { (it as Enum<*>).name == if (valueWide) "VALUE_WIDE" else "BALANCED" }
        val component = Class.forName("${owner.name}\$Result", true, loader).getMethod("getComponent")
        return { rows, style, width ->
            val selected = frame.enumConstants.first { (it as Enum<*>).name == style.name }
            val ownedRows = rows.map { (label, value) -> makePair.newInstance(label, value) }
            val result = if (options == null) method.invoke(instance, ownedRows, null, selected, width, balanced)
                else method.invoke(instance, ownedRows, null, selected, width, balanced, options)
            component.invoke(result) as Component
        }
    }

    fun prose(text: Component, width: Int = 468): PaperDialogBody {
        val aligned = try {
            val owner = Class.forName("ru.arc.gui.DialogTextLayout")
            val alignment = Class.forName("ru.arc.text.TextAlignment", true, owner.classLoader)
            val left = alignment.enumConstants.first { (it as Enum<*>).name == "LEFT" }
            val result = owner.getMethod("layout", Component::class.java, alignment, Int::class.javaPrimitiveType)
                .invoke(owner.getField("INSTANCE").get(null), text, left, width)
            result.javaClass.getMethod("getComponent").invoke(result) as Component
        } catch (_: ReflectiveOperationException) {
            text
        } catch (_: LinkageError) {
            text
        }
        return PaperDialogBody(aligned, width)
    }

    fun body(rows: List<Pair<Component, Component>>, frame: Frame = Frame.EPIC, width: Int = 320, rowSeparators: Boolean = false, valueWide: Boolean = false): PaperDialogBody {
        val rendered = try {
            (when {
                valueWide && rowSeparators -> separatedWideRenderer
                valueWide -> wideRenderer
                rowSeparators -> separatedRenderer
                else -> renderer
            } ?: renderer)?.invoke(rows, frame, width)
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
