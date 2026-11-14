package com.nazmiefearmutcu.flowmap.mobile.ui.screens.news

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nazmiefearmutcu.flowmap.mobile.core.Fmt
import com.nazmiefearmutcu.flowmap.mobile.data.FearGreed
import com.nazmiefearmutcu.flowmap.mobile.data.news.FearGreedRepository
import com.nazmiefearmutcu.flowmap.mobile.data.news.FeedHealth
import com.nazmiefearmutcu.flowmap.mobile.data.news.NewsCategory
import com.nazmiefearmutcu.flowmap.mobile.data.news.NewsRepository
import com.nazmiefearmutcu.flowmap.mobile.data.news.Story
import com.nazmiefearmutcu.flowmap.mobile.ui.components.Card
import com.nazmiefearmutcu.flowmap.mobile.ui.components.ErrorBanner
import com.nazmiefearmutcu.flowmap.mobile.ui.components.FilterChipRow
import com.nazmiefearmutcu.flowmap.mobile.ui.components.SplitBar
import com.nazmiefearmutcu.flowmap.mobile.ui.theme.Fm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NewsUi(
    val loading: Boolean = true,
    val stories: List<Story> = emptyList(),
    val health: FeedHealth? = null,
    val fetchedAt: Long = 0L,
    val fng: FearGreed? = null,
    val error: String? = null,
)

class NewsViewModel : ViewModel() {
    private val _ui = MutableStateFlow(NewsUi())
    val ui = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val fng = try { FearGreedRepository.history(1).lastOrNull() } catch (_: Exception) { _ui.value.fng }
            try {
                val r = NewsRepository.load()
                _ui.value = NewsUi(false, r.stories, r.health, r.fetchedAt, fng, if (r.stories.isEmpty()) "No stories could be loaded. Check your connection." else null)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, fng = fng, error = "News unavailable: ${e.message}")
            }
        }
    }
}

private val categories = listOf<NewsCategory?>(null) + NewsCategory.entries

/** Awareness-style news: tiered publishers, quality-gated, clustered into stories with sentiment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen() {
    val vm: NewsViewModel = viewModel { NewsViewModel() }
    val ui by vm.ui.collectAsState()
    var cat by rememberSaveable { mutableStateOf(0) }
    val ctx = LocalContext.current
    val shown = ui.stories.filter { s -> categories[cat]?.let { c -> s.lead.category == c || s.related.any { it.category == c } } ?: true }
    val pos = shown.count { it.sentiment > 0.15 }
    val neg = shown.count { it.sentiment < -0.15 }

    PullToRefreshBox(isRefreshing = ui.loading && ui.stories.isNotEmpty(), onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Text("News", color = Fm.text, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                Text(
                    ui.health?.let { "${it.ok} of ${it.ok + it.failed} feeds · updated ${Fmt.timeMin(ui.fetchedAt)}" } ?: "Tier 1–3 publishers only",
                    color = Fm.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Headline tone", color = Fm.textDim, fontSize = 12.sp)
                                Text("$pos positive · $neg negative · ${shown.size - pos - neg} neutral", color = Fm.text, fontSize = 14.sp)
                            }
                            ui.fng?.let {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Fear & Greed", color = Fm.textDim, fontSize = 12.sp)
                                    Text("${it.value} · ${it.label}", color = Fm.yellow, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SplitBar(if (pos + neg > 0) pos / (pos + neg).toFloat() else 0.5f, Fm.buy, Fm.sell)
                        Text("Tone is a word-list estimate from headlines, not a forecast.", color = Fm.textFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item {
                FilterChipRow(categories.map { it?.label ?: "All" }, setOf(cat), { cat = it }, Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(6.dp))
                ui.error?.let { ErrorBanner(it, onRetry = vm::refresh, modifier = Modifier.padding(12.dp)) }
                if (ui.loading && ui.stories.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Fm.cyan) }
            }
            items(shown.take(150), key = { it.lead.url }) { s ->
                StoryCard(s) { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
    }
}

@Composable
private fun StoryCard(s: Story, open: (String) -> Unit) {
    val tone = when {
        s.sentiment > 0.15 -> Fm.buy
        s.sentiment < -0.15 -> Fm.sell
        else -> Fm.textFaint
    }
    Column(Modifier.fillMaxWidth().clickable { open(s.lead.url) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TierBadge(s.lead.tier)
            Text(s.lead.publisher, color = Fm.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("· ${s.lead.category.label} · ${if (s.lead.published > 0) Fmt.ago(s.lead.published) else "undated"}", color = Fm.textDim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
        }
        Spacer(Modifier.height(4.dp))
        Text(s.lead.title, color = Fm.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp)
        if (s.lead.summary.isNotBlank()) {
            Text(s.lead.summary, color = Fm.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (s.related.isNotEmpty()) {
            Text(
                "+${s.related.size} more from ${s.related.map { it.publisher }.distinct().joinToString(", ")}",
                color = Fm.textFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TierBadge(tier: Int) {
    val c = when (tier) {
        1 -> Fm.yellow
        2 -> Fm.cyan
        else -> Fm.textDim
    }
    Box(Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, c, RoundedCornerShape(4.dp)).background(Color.Transparent).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text("T$tier", color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
