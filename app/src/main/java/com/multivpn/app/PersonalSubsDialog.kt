package com.multivpn.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.uciran.uctor.ui.Surface1

private val SubTextMain = Color(0xFFEDEDF5)
private val SubTextSub = Color(0xFFB4B4C8)
private val SubDanger = Color(0xFFFF6B6B)

/** ساب‌لینک‌های شخصی: لیست با نام، تیک فعال، ویرایش و حذف. */
@Composable
fun PersonalSubsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val list = SingBoxController.personal
    var editIndex by remember { mutableStateOf(-1) }
    var showAddEdit by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("ساب‌لینک‌های شخصی", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (list.isEmpty()) {
                    Text("هنوز ساب‌لینکی اضافه نشده", color = SubTextSub, fontSize = 13.sp)
                }
                list.forEachIndexed { i, sub ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = sub.enabled,
                            onCheckedChange = { SingBoxController.togglePersonal(context, i) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                sub.name,
                                color = SubTextMain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                sub.url,
                                color = SubTextSub,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = {
                            editIndex = i
                            showAddEdit = true
                        }) { Text("ویرایش", fontSize = 12.sp) }
                        TextButton(onClick = { SingBoxController.deletePersonal(context, i) }) {
                            Text("حذف", color = SubDanger, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                editIndex = -1
                showAddEdit = true
            }) { Text("افزودن ساب‌لینک") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        },
    )

    if (showAddEdit) {
        val existing = if (editIndex in list.indices) list[editIndex] else null
        AddEditSubDialog(
            context = context,
            existing = existing,
            onSave = { name, url ->
                if (existing == null) SingBoxController.addPersonal(context, name, url)
                else SingBoxController.editPersonal(context, editIndex, name, url)
                showAddEdit = false
            },
            onDismiss = { showAddEdit = false },
        )
    }
}

@Composable
private fun AddEditSubDialog(
    context: Context,
    existing: PersonalSub?,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(if (existing == null) "افزودن ساب‌لینک" else "ویرایش ساب‌لینک", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم دلخواه") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("لینک ساب‌لینک") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotBlank()) {
                    Text(error, color = SubDanger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val u = url.trim()
                if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) {
                    error = "لینک باید با https:// شروع شود"
                    return@Button
                }
                val n = name.trim().ifBlank { "ساب‌لینک ${(SingBoxController.personal.size + 1)}" }
                onSave(n, u)
            }) { Text("ذخیره") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        },
    )
}
