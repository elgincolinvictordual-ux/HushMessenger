package app.hushmessenger.patches.misc

import app.hushmessenger.patches.MessengerTarget
import app.hushmessenger.patches.controls.hookId
import app.hushmessenger.patches.controls.settingsExtension
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val CHAT_PEEK = "Lapp/hushmessenger/extension/ChatPeek;"
private const val MENU_FRAGMENT = "Lcom/facebook/messaging/threadlist/threaditemmenu/InboxThreadContextMenuFragment;"
private const val THREAD_SUMMARY = "Lcom/facebook/messaging/model/threads/ThreadSummary;"
private const val VIEW = "Landroid/view/View;"
private const val OBJ = "Ljava/lang/Object;"

/**
 * The method that opens a chat row's long press menu: static, returns nothing, takes the pressed row's View first and the
 * ThreadSummary fourth (eight parameters in all), and creates the menu fragment. Matched by shape, because Messenger's
 * own names for it change from build to build.
 */
private fun Method.opensChatMenu(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "V") return false
    val params = parameterTypes.map { it.toString() }
    if (params.size != 8 || params[0] != VIEW || params[3] != THREAD_SUMMARY) return false
    return implementation?.instructions?.any {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as? TypeReference)?.type == MENU_FRAGMENT
    } == true
}

@Suppress("unused")
val chatPeekPatch = bytecodePatch(
    name = "Chat peek preview",
    description = "Long press a chat to see a card with its last message above the menu, like the iPhone peek.",
    default = true,
) {
    compatibleWith(MessengerTarget.COMPATIBILITY)
    dependsOn(settingsExtension)
    execute {
        val classes = mutableListOf<ClassDef>()
        classDefForEach { classes.add(it) }

        val launchers = classes.flatMap { it.methods }.filter { it.opensChatMenu() }
        val launcher = launchers.singleOrNull()
            ?: throw PatchException("Chat peek: expected one chat menu launcher, found ${launchers.size}")
        val menu = classes.singleOrNull { it.type == MENU_FRAGMENT }
            ?: throw PatchException("Chat peek: the chat menu fragment is missing")
        val onStart = menu.methods.singleOrNull { it.name == "onStart" && it.returnType == "V" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("Chat peek: the chat menu fragment has no onStart")

        mutableClassDefBy(launcher.definingClass).methods.single { it.hookId() == launcher.hookId() }.addInstructions(
            0,
            "invoke-static/range {p0 .. p7}, $CHAT_PEEK->launch($VIEW$OBJ$OBJ$OBJ$OBJ$OBJ$OBJ$OBJ)V",
        )
        mutableClassDefBy(MENU_FRAGMENT).methods.single { it.hookId() == onStart.hookId() }.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $CHAT_PEEK->menuStarted($OBJ)V",
        )
    }
}
