package io.github.clobrano.greenwave.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Editable fields of a traffic light. */
data class LightForm(val name: String, val speedLimitKmh: Int, val approachBearing: Double?)

@Composable
fun LightFormFields(
    form: LightForm,
    currentHeading: Double?,
    directionChosen: Boolean = true,
    onDirectionPicked: () -> Unit = {},
    onChange: (LightForm) -> Unit,
) {
    var limitText by remember { mutableStateOf(form.speedLimitKmh.toString()) }
    Column {
        OutlinedTextField(
            value = form.name,
            onValueChange = { onChange(form.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
        )
        OutlinedTextField(
            value = limitText,
            onValueChange = { text ->
                limitText = text.filter(Char::isDigit).take(3)
                limitText.toIntOrNull()?.let { onChange(form.copy(speedLimitKmh = it)) }
            },
            label = { Text("Speed limit (km/h)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text("Driving direction", Modifier.padding(top = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(if (directionChosen) directionLabel(form.approachBearing) else "Choose…")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    compassDirections.forEach { (label, bearing) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onChange(form.copy(approachBearing = bearing))
                                onDirectionPicked()
                                open = false
                            },
                        )
                    }
                }
            }
            if (currentHeading != null) {
                TextButton(onClick = { onChange(form.copy(approachBearing = currentHeading)); onDirectionPicked() }) {
                    Text("Use mine")
                }
            }
        }
        if (directionChosen && form.approachBearing == null) {
            Text(NO_DIRECTION_WARNING, color = Palette.amber, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun AddLightDialog(
    defaultName: String,
    currentHeading: Double?,
    onConfirm: (LightForm) -> Unit,
    onDismiss: () -> Unit,
) {
    // The direction is pre-filled with my heading when known; otherwise it must be chosen
    // explicitly (picking "Any" is allowed, but then the light is only recorded by hand).
    var form by remember { mutableStateOf(LightForm(defaultName, 50, currentHeading)) }
    var directionChosen by remember { mutableStateOf(currentHeading != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New traffic light") },
        text = {
            LightFormFields(
                form, currentHeading, directionChosen,
                onDirectionPicked = { directionChosen = true },
            ) { form = it }
        },
        confirmButton = {
            Button(onClick = { onConfirm(form) }, enabled = form.name.isNotBlank() && directionChosen) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
