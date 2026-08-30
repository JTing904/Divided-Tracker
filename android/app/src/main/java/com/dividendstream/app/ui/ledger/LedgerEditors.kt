package com.dividendstream.app.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dividendstream.app.core.toPriceInput
import com.dividendstream.app.data.remote.CashFlowDto
import com.dividendstream.app.data.remote.FundDto
import com.dividendstream.app.ui.components.DsTextField
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.theme.DividendColors
import java.math.BigDecimal
import java.time.LocalDate

/** Which editor is open, and what it is editing. Null when none is. */
sealed interface LedgerEditor {
    /** A recurring flow. [existing] is null when adding. */
    data class Flow(val direction: String, val existing: CashFlowDto? = null) : LedgerEditor

    /** One thing that happened. [existing] is null when adding. */
    data class Entry(
        val direction: String,
        val existing: com.dividendstream.app.data.remote.LedgerEntryDto? = null,
    ) : LedgerEditor

    data class Fund(val existing: FundDto? = null) : LedgerEditor

    /**
     * Money into or out of one fund. [direction] is DEPOSIT or WITHDRAWAL.
     *
     * [existing] is null when adding. Editing rather than deleting and re-entering matters
     * here: a mistyped amount is a correction to one event, not two events that cancel.
     */
    data class Movement(
        val fund: FundDto,
        val direction: String,
        val existing: com.dividendstream.app.data.remote.FundMovementDto? = null,
    ) : LedgerEditor
}

@Composable
fun LedgerEditorDialog(
    editor: LedgerEditor,
    viewModel: LedgerViewModel,
    isSaving: Boolean,
    onDismiss: () -> Unit,
) {
    when (editor) {
        is LedgerEditor.Flow -> FlowDialog(editor, viewModel, isSaving, onDismiss)
        is LedgerEditor.Entry -> EntryDialog(editor, viewModel, isSaving, onDismiss)
        is LedgerEditor.Fund -> FundDialog(editor, viewModel, isSaving, onDismiss)
        is LedgerEditor.Movement -> MovementDialog(editor, viewModel, isSaving, onDismiss)
    }
}

// --- recurring flow ----------------------------------------------------------

@Composable
private fun FlowDialog(
    editor: LedgerEditor.Flow,
    viewModel: LedgerViewModel,
    isSaving: Boolean,
    onDismiss: () -> Unit,
) {
    val existing = editor.existing
    val income = editor.direction == "INCOME"

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var amount by remember { mutableStateOf(existing?.amount?.toPriceInput().orEmpty()) }
    var period by remember { mutableStateOf(existing?.period ?: "MONTHLY") }
    var category by remember {
        mutableStateOf(LedgerIcon.of(existing?.category).takeIf { existing != null } ?: defaultIcon(income))
    }
    var startsOn by remember { mutableStateOf(existing?.startsOn?.toString() ?: thisMonthStart()) }
    var payday by remember { mutableStateOf(existing?.arrivesOn?.toString().orEmpty()) }

    val parsedAmount = amount.toAmountOrNull()
    val parsedStart = startsOn.toDateOrNull()
    // Only weekly and monthly can name a day: a day cannot pay on some other day, and a year
    // would need a date rather than a number.
    val paydayApplies = period == "WEEKLY" || period == "MONTHLY"
    val paydayMax = if (period == "WEEKLY") 7 else 31
    val parsedPayday = payday.trim().toIntOrNull()?.takeIf { it in 1..paydayMax }
    val paydayValid = payday.isBlank() || parsedPayday != null
    val canSave = name.isNotBlank() && parsedAmount != null && parsedStart != null &&
        paydayValid && !isSaving

    EditorScaffold(
        title = if (existing != null) "Edit ${existing.name}" else if (income) "Money coming in" else "Money going out",
        confirmLabel = if (isSaving) "Saving…" else "Save",
        canConfirm = canSave,
        onConfirm = {
            viewModel.saveFlow(
                id = existing?.id,
                name = name.trim(),
                direction = editor.direction,
                amount = parsedAmount!!,
                period = period,
                category = category.key,
                arrivesOn = parsedPayday.takeIf { paydayApplies },
                startsOn = parsedStart,
                endsOn = existing?.endsOn,
                onSaved = onDismiss,
            )
        },
        onDismiss = onDismiss,
    ) {
        IconPicker(
            options = if (income) LedgerIcon.income else LedgerIcon.expense,
            selected = category,
            onSelect = { category = it },
        )
        Spacer(Modifier.height(16.dp))

        DsTextField(
            label = "Name",
            value = name,
            onValueChange = { name = it },
            placeholder = if (income) "Salary" else "Rent",
        )
        Spacer(Modifier.height(12.dp))

        DsTextField(
            label = "Amount",
            value = amount,
            onValueChange = { amount = it },
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
        )
        Spacer(Modifier.height(16.dp))

        OverlineText("How often")
        Spacer(Modifier.height(8.dp))
        // All four are offered as equals. Somebody on an allowance has no monthly figure to
        // convert, and asking them to work one out is the app doing arithmetic at them.
        ChoiceRow(
            options = PERIODS,
            selected = period,
            label = { it.second },
            key = { it.first },
            onSelect = { period = it.first },
        )
        Spacer(Modifier.height(16.dp))

        if (paydayApplies) {
            // A wage is nothing until it lands and all of it after, so the day it lands on is
            // the difference between a fund holding money and a fund pretending to. Blank is
            // allowed and means the end of the period, which is what every flow did before
            // this field existed.
            DsTextField(
                label = if (period == "WEEKLY") "Paid on (1 = Monday)" else "Paid on (day of month)",
                value = payday,
                onValueChange = { payday = it },
                placeholder = if (period == "WEEKLY") "5" else "28",
                keyboardType = KeyboardType.Number,
                isError = !paydayValid,
                supportingText = when {
                    !paydayValid -> "A number from 1 to $paydayMax"
                    period == "WEEKLY" -> "Leave blank and it counts at the end of the week."
                    else -> "Leave blank and it counts at the end of the month. 31 means the " +
                        "last day, whatever that is."
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        DsTextField(
            label = "Running since",
            value = startsOn,
            onValueChange = { startsOn = it },
            placeholder = "YYYY-MM-DD",
            imeAction = ImeAction.Done,
            isError = startsOn.isNotBlank() && parsedStart == null,
            supportingText = "Defaults to the 1st of this month. Change it if it really began later.",
        )
    }
}

// --- one recorded thing ------------------------------------------------------

@Composable
private fun EntryDialog(
    editor: LedgerEditor.Entry,
    viewModel: LedgerViewModel,
    isSaving: Boolean,
    onDismiss: () -> Unit,
) {
    val existing = editor.existing
    var direction by remember { mutableStateOf(existing?.direction ?: editor.direction) }
    var amount by remember { mutableStateOf(existing?.amount?.toPriceInput().orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var category by remember {
        mutableStateOf(
            if (existing != null) LedgerIcon.of(existing.category)
            else defaultIcon(income = false),
        )
    }
    var occurredOn by remember {
        mutableStateOf((existing?.occurredOn ?: LocalDate.now()).toString())
    }

    val income = direction == "INCOME"
    val parsedAmount = amount.toAmountOrNull()
    val parsedDate = occurredOn.toDateOrNull()

    EditorScaffold(
        title = if (existing != null) "Fix this record" else "Write it down",
        confirmLabel = if (isSaving) "Saving…" else if (existing != null) "Save" else "Record",
        canConfirm = parsedAmount != null && parsedDate != null && !isSaving,
        onConfirm = {
            viewModel.saveEntry(
                id = existing?.id,
                direction = direction,
                amount = parsedAmount!!,
                occurredOn = parsedDate,
                category = category.key,
                note = note.trim().takeIf { it.isNotEmpty() },
                onSaved = onDismiss,
            )
        },
        onDismiss = onDismiss,
    ) {
        ChoiceRow(
            options = listOf("EXPENSE" to "Spent", "INCOME" to "Received"),
            selected = direction,
            label = { it.second },
            key = { it.first },
            onSelect = {
                direction = it.first
                category = defaultIcon(income = it.first == "INCOME")
            },
        )
        Spacer(Modifier.height(16.dp))

        IconPicker(
            options = if (income) LedgerIcon.income else LedgerIcon.expense,
            selected = category,
            onSelect = { category = it },
        )
        Spacer(Modifier.height(16.dp))

        DsTextField(
            label = "Amount",
            value = amount,
            onValueChange = { amount = it },
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
        )
        Spacer(Modifier.height(12.dp))

        DsTextField(
            label = "Note",
            value = note,
            onValueChange = { note = it },
            placeholder = "Optional",
        )
        Spacer(Modifier.height(16.dp))

        OverlineText("When")
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            options = listOf(
                LocalDate.now() to "Today",
                LocalDate.now().minusDays(1) to "Yesterday",
            ),
            selected = parsedDate,
            label = { it.second },
            key = { it.first },
            onSelect = { occurredOn = it.first.toString() },
        )
        Spacer(Modifier.height(10.dp))
        DsTextField(
            label = "Or a date",
            value = occurredOn,
            onValueChange = { occurredOn = it },
            placeholder = "YYYY-MM-DD",
            imeAction = ImeAction.Done,
            isError = occurredOn.isNotBlank() && parsedDate == null,
        )
    }
}

// --- a fund ------------------------------------------------------------------

@Composable
private fun FundDialog(
    editor: LedgerEditor.Fund,
    viewModel: LedgerViewModel,
    isSaving: Boolean,
    onDismiss: () -> Unit,
) {
    val existing = editor.existing
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var percent by remember { mutableStateOf(existing?.percent?.toPriceInput().orEmpty()) }
    var icon by remember {
        mutableStateOf(LedgerIcon.of(existing?.icon).takeIf { existing != null } ?: LedgerIcon.Savings)
    }

    val parsedPercent = percent.toAmountOrNull()

    EditorScaffold(
        title = if (existing != null) "Edit ${existing.name}" else "A place for what is left",
        confirmLabel = if (isSaving) "Saving…" else "Save",
        canConfirm = name.isNotBlank() && parsedPercent != null && !isSaving,
        onConfirm = {
            viewModel.saveFund(
                id = existing?.id,
                name = name.trim(),
                percent = parsedPercent!!,
                icon = icon.key,
                onSaved = onDismiss,
            )
        },
        onDismiss = onDismiss,
    ) {
        IconPicker(options = LedgerIcon.fund, selected = icon, onSelect = { icon = it })
        Spacer(Modifier.height(16.dp))

        DsTextField(
            label = "Name",
            value = name,
            onValueChange = { name = it },
            placeholder = "Emergency fund",
        )
        Spacer(Modifier.height(12.dp))

        DsTextField(
            label = "Share of what is left",
            value = percent,
            onValueChange = { percent = it },
            placeholder = "30",
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
            supportingText = "A percentage, so it keeps up when your income changes.",
        )
        Spacer(Modifier.height(10.dp))
        // Quick picks, because the common answers are round numbers and typing "50" on a
        // phone keyboard is three taps more than it needs to be.
        ChoiceRow(
            options = listOf("10", "20", "25", "30", "50"),
            selected = percent.trim(),
            label = { "$it%" },
            key = { it },
            onSelect = { percent = it },
        )
    }
}

// --- money into or out of a fund ---------------------------------------------

@Composable
private fun MovementDialog(
    editor: LedgerEditor.Movement,
    viewModel: LedgerViewModel,
    isSaving: Boolean,
    onDismiss: () -> Unit,
) {
    val fund = editor.fund
    val existing = editor.existing
    val depositing = (existing?.direction ?: editor.direction) == "DEPOSIT"

    var amount by remember { mutableStateOf(existing?.amount?.toPriceInput().orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var occurredOn by remember {
        mutableStateOf((existing?.occurredOn ?: LocalDate.now()).toString())
    }

    val parsedAmount = amount.toAmountOrNull()
    val parsedDate = occurredOn.toDateOrNull()
    // Said, not enforced. Spending more than a fund holds means borrowing from it, which is a
    // real thing to record; the app's job is to make sure the person knows they are doing it.
    val goesNegative = !depositing && parsedAmount != null &&
        parsedAmount.subtract(fund.balance).signum() > 0

    EditorScaffold(
        title = when {
            existing != null -> "Fix this entry"
            depositing -> "Add to ${fund.name}"
            else -> "Spend from ${fund.name}"
        },
        confirmLabel = when {
            isSaving -> "Saving…"
            existing != null -> "Save"
            depositing -> "Add"
            else -> "Spend"
        },
        canConfirm = parsedAmount != null && parsedDate != null && !isSaving,
        onConfirm = {
            viewModel.moveFundMoney(
                fundId = fund.id,
                id = existing?.id,
                direction = existing?.direction ?: editor.direction,
                amount = parsedAmount!!,
                occurredOn = parsedDate,
                note = note.trim().takeIf { it.isNotEmpty() },
                onSaved = onDismiss,
            )
        },
        onDismiss = onDismiss,
    ) {
        Text(
            if (existing != null) {
                "Change the amount or the date, or delete it from the list if it never happened."
            } else if (depositing) {
                "This fund already fills itself with ${fund.percent.toPlainString()}% of what " +
                    "is left over. Add money here only when you put in something extra."
            } else {
                if (fund.balance.signum() < 0) {
                    "This fund is ${fund.balance.abs().setScale(2, java.math.RoundingMode.DOWN)
                        .toPlainString()} short. Your share is paying it back."
                } else {
                    "There is about ${fund.balance.setScale(2, java.math.RoundingMode.DOWN)
                        .toPlainString()} in this fund."
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        DsTextField(
            label = "Amount",
            value = amount,
            onValueChange = { amount = it },
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            supportingText = if (goesNegative) {
                "More than this fund holds. It will go below zero and pay itself back from " +
                    "your share over the coming months."
            } else {
                null
            },
        )
        Spacer(Modifier.height(12.dp))

        DsTextField(
            label = "Note",
            value = note,
            onValueChange = { note = it },
            placeholder = if (depositing) "Optional" else "What was it for?",
        )
        Spacer(Modifier.height(16.dp))

        OverlineText("When")
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            options = listOf(
                LocalDate.now() to "Today",
                LocalDate.now().minusDays(1) to "Yesterday",
            ),
            selected = parsedDate,
            label = { it.second },
            key = { it.first },
            onSelect = { occurredOn = it.first.toString() },
        )
        Spacer(Modifier.height(10.dp))
        DsTextField(
            label = "Or a date",
            value = occurredOn,
            onValueChange = { occurredOn = it },
            placeholder = "YYYY-MM-DD",
            imeAction = ImeAction.Done,
            isError = occurredOn.isNotBlank() && parsedDate == null,
        )
    }
}

// --- shared pieces -----------------------------------------------------------

@Composable
private fun EditorScaffold(
    title: String,
    confirmLabel: String,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        },
        text = {
            // Capped and scrollable: the flow editor is taller than a short phone in landscape,
            // and a dialog that cannot reach its own Save button is a dead end.
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) {
                Text(
                    confirmLabel,
                    color = if (canConfirm) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** A horizontal strip of icons. The point of the screen having any personality at all. */
@Composable
private fun IconPicker(
    options: List<LedgerIcon>,
    selected: LedgerIcon,
    onSelect: (LedgerIcon) -> Unit,
) {
    Column {
        OverlineText("Kind")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(option.tint.copy(alpha = if (isSelected) 0.28f else 0.12f))
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) option.tint else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable { onSelect(option) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.emoji,
                            fontSize = 21.sp,
                            lineHeight = 21.sp,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) option.tint else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A row of mutually exclusive chips. */
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: Any?,
    label: (T) -> String,
    key: (T) -> Any,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = key(option) == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) DividendColors.GrowthGlow
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val PERIODS = listOf(
    "DAILY" to "A day",
    "WEEKLY" to "A week",
    "MONTHLY" to "A month",
    "YEARLY" to "A year",
)

private fun defaultIcon(income: Boolean) = if (income) LedgerIcon.Salary else LedgerIcon.Food

private fun thisMonthStart(): String = LocalDate.now().withDayOfMonth(1).toString()

/** Rejects anything that is not a positive number, so an empty field is never sent as zero. */
private fun String.toAmountOrNull(): BigDecimal? =
    trim().replace(",", "").takeIf { it.isNotEmpty() }
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }
        ?.takeIf { it.signum() > 0 }

private fun String.toDateOrNull(): LocalDate? =
    trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
