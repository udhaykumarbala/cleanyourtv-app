package tv.cleanyour.ui.design.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tv.cleanyour.ui.design.R

// S1c "Type the TV's address": a numeric field and Connect. Setup's address sheet and, since 0.4.8, the debugging
// step's "Enter the TV's address" for a TV the phone knows. The caller checks the address (isHomeAddress) and sets
// `error` when it is not one on a home network.
@Composable
fun AddressEntry(text: String, onText: (String) -> Unit, error: Boolean, onConnect: () -> Unit) {
    SheetHeader(stringResource(R.string.cyt_address_title), subtitle = stringResource(R.string.cyt_address_body))
    CytTextField(
        text, onText, stringResource(R.string.cyt_address_field_label), Modifier.padding(top = 16.dp),
        error = if (error) stringResource(R.string.cyt_address_error) else null, keyboardType = KeyboardType.Decimal,
    )
    PrimaryButton(stringResource(R.string.cyt_address_connect), onConnect, Modifier.padding(top = 16.dp), enabled = text.count { it == '.' } == 3)
}

// What the field takes: digits and dots, at most 15 characters.
fun addressText(input: String): String = input.filter { c -> c.isDigit() || c == '.' }.take(15)
