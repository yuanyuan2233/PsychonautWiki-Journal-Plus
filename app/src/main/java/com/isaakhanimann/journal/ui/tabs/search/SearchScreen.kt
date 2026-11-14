/*
 * Copyright (c) 2023. Isaak Hanimann.
 * This file is part of PsychonautWiki Journal.
 *
 * PsychonautWiki Journal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * PsychonautWiki Journal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PsychonautWiki Journal.  If not, see https://www.gnu.org/licenses/gpl-3.0.en.html.
 */

package com.isaakhanimann.journal.ui.tabs.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.ui.tabs.search.substancerow.SubstanceRow
import com.isaakhanimann.journal.ui.theme.horizontalPadding

@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel = hiltViewModel(),
    onSubstanceTap: (substanceModel: SubstanceModel) -> Unit,
    onCustomSubstanceTap: (customSubstanceId: Int) -> Unit,
    navigateToAddCustomSubstanceScreen: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    Scaffold(
        floatingActionButton = {
            if (!isFocused) {
                FloatingActionButton(
                    onClick = { focusRequester.requestFocus() }) {
                    Icon(Icons.Default.Keyboard, contentDescription = stringResource(R.string.drugs_keyboard))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            val searchText by searchViewModel.searchTextFlow.collectAsStateWithLifecycle()
            val categories by searchViewModel.chipCategoriesFlow.collectAsStateWithLifecycle()
            val activeFilters = remember(categories) { categories.filter { it.isActive } }
            val onFilterTapped = searchViewModel::onFilterTapped
            val filteredSubstances by searchViewModel.filteredSubstancesFlow.collectAsStateWithLifecycle()
            val filteredCustomSubstances by searchViewModel.filteredCustomSubstancesFlow.collectAsStateWithLifecycle()
            SearchField(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                    },
                searchText = searchText,
                onChange = {
                    searchViewModel.filterSubstances(searchText = it)
                },
                categories = categories,
                onFilterTapped = onFilterTapped,
                isShowingFilter = true
            )
            if (activeFilters.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    items(
                        items = activeFilters,
                        key = { it.chipName }
                    ) { categoryChipModel ->
                        CategoryChipDelete(categoryChipModel = categoryChipModel) {
                            onFilterTapped(categoryChipModel.chipName)
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }
            if (filteredSubstances.isEmpty() && filteredCustomSubstances.isEmpty()) {
                Column {
                    val activeCategoryDisplayNames =
                        activeFilters.filter { it.isActive }.map { getCategoryDisplayName(it.chipName) }
                    if (activeCategoryDisplayNames.isEmpty()) {
                        Text(stringResource(R.string.drugs_no_matching_substance_found), modifier = Modifier.padding(10.dp))
                    } else if (activeCategoryDisplayNames.size == 1) {
                        Text(
                            stringResource(R.string.drugs_no_matching_substance_with_tag_found, activeCategoryDisplayNames[0]),
                            modifier = Modifier.padding(10.dp)
                        )
                    } else {
                        val names = activeCategoryDisplayNames.joinToString(separator = "', '")
                        Text(
                            stringResource(R.string.drugs_no_matching_substance_with_tags_found, names),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    TextButton(
                        onClick = navigateToAddCustomSubstanceScreen,
                        modifier = Modifier.padding(horizontal = horizontalPadding)
                    ) {
                        Icon(
                            Icons.Outlined.Add, contentDescription = stringResource(R.string.drugs_add)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(text = stringResource(R.string.drugs_add_custom_substance))
                    }
                }
            } else {
                LazyColumn {
                    items(
                        items = filteredCustomSubstances,
                        key = { "cs_${it.id}" }
                    ) { customSubstance ->
                        SubstanceRow(substanceModel = SubstanceModel(
                            name = customSubstance.name,
                            commonNames = emptyList(),
                            categories = listOf(
                                CategoryModel(
                                    name = "custom", color = customColor
                                )
                            ),
                            hasSaferUse = false,
                            hasInteractions = false
                        ), onTap = {
                            onCustomSubstanceTap(customSubstance.id)
                        })
                        HorizontalDivider()
                    }
                    items(
                        items = filteredSubstances,
                        key = { "sub_${it.name}" }
                    ) { substance ->
                        SubstanceRow(substanceModel = substance, onTap = {
                            onSubstanceTap(substance)
                        })
                        HorizontalDivider()
                    }

                    item {
                        val addCustomSubstanceText = stringResource(R.string.drugs_add_custom_substance)
                        TextButton(
                            onClick = navigateToAddCustomSubstanceScreen,
                            modifier = Modifier
                                .padding(horizontal = horizontalPadding)
                                .semantics { contentDescription = addCustomSubstanceText }
                        ) {
                            Icon(
                                Icons.Outlined.Add, contentDescription = stringResource(R.string.drugs_add)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(text = addCustomSubstanceText)
                        }
                    }
                }
            }
        }
    }
}
