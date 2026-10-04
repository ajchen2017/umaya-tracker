package tw.umaya.tracker.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import tw.umaya.tracker.data.Prefs
import java.security.MessageDigest

/**
 * 身分鎖定: a phone is either the 登山者's or a 留守人's. The role is picked once (with a PIN) and the
 * app opens straight into it afterwards; switching back to the picker needs that PIN, so a guardian's
 * phone doesn't wander into the tracker (and vice versa).
 */
object RoleLock {
    const val HIKER = "hiker"
    const val GUARDIAN = "guardian"

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("umaya-role:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun pinField(activity: Activity, hint: String) = EditText(activity).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun box(activity: Activity, vararg fields: EditText) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        fields.forEach { addView(it) }
    }

    /** Asks for a new 4–6 digit PIN twice; [onDone] gets its hash. */
    fun askNewPin(activity: Activity, onDone: (String) -> Unit) {
        val a = pinField(activity, "PIN（4–6 位數字）"); val b = pinField(activity, "再輸入一次")
        AlertDialog.Builder(activity)
            .setTitle("設定身分 PIN")
            .setMessage("之後要切換身分（登山者 ↔ 留守人）時需要輸入這組 PIN。")
            .setView(box(activity, a, b))
            .setCancelable(false)
            .setPositiveButton("確定", null)
            .setNegativeButton("取消", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val p = a.text.toString()
                        when {
                            p.length !in 4..6 -> Toast.makeText(activity, "PIN 需為 4–6 位數字", Toast.LENGTH_SHORT).show()
                            p != b.text.toString() -> Toast.makeText(activity, "兩次輸入的 PIN 不同", Toast.LENGTH_SHORT).show()
                            else -> { dismiss(); onDone(hash(p)) }
                        }
                    }
                }
            }.show()
    }

    /** Runs [onOk] once the PIN is entered correctly (straight away if none was ever set). */
    fun verify(activity: Activity, prefs: Prefs, onOk: () -> Unit) {
        val stored = prefs.rolePinHash ?: return onOk()
        val f = pinField(activity, "PIN")
        AlertDialog.Builder(activity)
            .setTitle("切換身分")
            .setMessage("請輸入身分 PIN。")
            .setView(box(activity, f))
            .setPositiveButton("確定", null)
            .setNegativeButton("取消", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (hash(f.text.toString()) == stored) { dismiss(); onOk() }
                        else { f.text.clear(); Toast.makeText(activity, "PIN 錯誤", Toast.LENGTH_SHORT).show() }
                    }
                }
            }.show()
    }

    /** PIN, then back to the role picker. */
    fun switchRole(activity: Activity) {
        val prefs = Prefs(activity)
        verify(activity, prefs) {
            prefs.appRole = null
            activity.startActivity(
                Intent(activity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            activity.finish()
        }
    }
}
