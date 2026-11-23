package app.rasidi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.rasidi.core.money.AmountParser
import app.rasidi.core.money.CurrencyDef
import app.rasidi.core.money.Fmt
import app.rasidi.core.money.Money
import app.rasidi.ui.theme.LocalR
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** حالة حقل مبلغ: يقبل الأرقام العربية والفارسية والغربية ويعرض القيمة الموحّدة عند الالتباس. */
class AmountState(initial: String = "") {
    var text by mutableStateOf(initial)
    var confirmedAmbiguous by mutableStateOf(false)
    fun parse(c: CurrencyDef?): Money? = if (c == null) null else AmountParser.parseMoney(text, c)
    fun result() = AmountParser.parse(text)
    val needsConfirm: Boolean get() = result()?.ambiguous == true && !confirmedAmbiguous
}

@Composable
fun AmountField(state: AmountState, currency: CurrencyDef?, label: String = "المبلغ", modifier: Modifier = Modifier, imeDone: Boolean = false) {
    val c = LocalR.current
    val r = state.result()
    val m = state.parse(currency)
    val err = state.text.isNotBlank() && (r == null || (currency != null && m == null))
    Column(modifier) {
        OutlinedTextField(
            value = state.text, onValueChange = { state.text = it; state.confirmedAmbiguous = false },
            label = { Text(label + (currency?.let { " (${it.code})" } ?: "")) },
            singleLine = true, isError = err,
            textStyle = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
            supportingText = {
                when {
                    err && r == null -> Text("رقم غير صالح")
                    err -> Text("خانات عشرية أكثر من المسموح لـ ${currency?.code}")
                    r != null && r.ambiguous -> Text("سيُحفظ كـ ${m?.let { Fmt.money(it, false) } ?: r.normalized} — تأكد أن الفاصل للآلاف", color = c.warn)
                    m != null -> Text("= ${Fmt.money(m, false)}", color = c.mute)
                    else -> {}
                }
            },
        )
        if (r?.ambiguous == true && !state.confirmedAmbiguous) {
            Row { TextButton(onClick = { state.confirmedAmbiguous = true }) { Text("نعم، ${m?.let { Fmt.money(it, false) }}") }
                TextButton(onClick = { state.text = state.text.replace(',', '.').let { t -> if (t.count { it == '.' } == 1) t else t } ; state.confirmedAmbiguous = true }) { Text("لا، الفاصل عشري") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: LocalDate, onChange: (LocalDate) -> Unit, label: String = "التاريخ", modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
        Icon(Icons.Filled.CalendarMonth, null); Spacer(Modifier.width(8.dp))
        Text("$label: ${if (date == LocalDate.now()) "اليوم " else ""}$date")
    }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text("اختيار") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("إلغاء") } }) { DatePicker(st) }
    }
}

@Composable
fun TextIn(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = singleLine, shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = modifier.fillMaxWidth())
}
