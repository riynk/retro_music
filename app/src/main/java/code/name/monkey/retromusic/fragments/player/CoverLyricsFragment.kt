package code.name.monkey.retromusic.fragments.player

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SHOW_LYRICS
import code.name.monkey.retromusic.databinding.FragmentCoverLyricsBinding
import code.name.monkey.retromusic.fragments.NowPlayingScreen
import code.name.monkey.retromusic.fragments.base.AbsMusicServiceFragment
import code.name.monkey.retromusic.fragments.base.goToLyrics
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.lyrics.KaraokeLyricsView
import code.name.monkey.retromusic.lyrics.LyricsDisplaySettings
import code.name.monkey.retromusic.lyrics.LyricsDocumentLoader
import code.name.monkey.retromusic.lyrics.LyricsSurface
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CoverLyricsFragment : AbsMusicServiceFragment(R.layout.fragment_cover_lyrics),
    MusicProgressViewUpdateHelper.Callback, SharedPreferences.OnSharedPreferenceChangeListener {

    private var progressViewUpdateHelper: MusicProgressViewUpdateHelper? = null
    private var _binding: FragmentCoverLyricsBinding? = null
    private val binding get() = _binding!!
    private val lyricsView: KaraokeLyricsView get() = binding.lyricsView
    private var lyricsLoadJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCoverLyricsBinding.bind(view)
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this, 33, 500)

        if (PreferenceUtil.nowPlayingScreen == NowPlayingScreen.Fit ||
            PreferenceUtil.nowPlayingScreen == NowPlayingScreen.Full
        ) {
            binding.root.background = null
        }

        lyricsView.apply {
            setStyle(LyricsDisplaySettings.read(requireContext(), LyricsSurface.OVER_COVER))
            setOnLineClickListener { time ->
                MusicPlayerRemote.seekTo(time.toInt())
                MusicPlayerRemote.resumePlaying()
            }
            setOnClickListener { goToLyrics(requireActivity()) }
            setOnTranslationRetryListener { updateLyrics() }
        }

        binding.root.isVisible = PreferenceUtil.showLyrics
        if (PreferenceUtil.showLyrics) {
            progressViewUpdateHelper?.start()
            updateLyrics()
        }
    }

    fun setColors(color: MediaNotificationProcessor) {
        if (_binding == null) return
        binding.playerLyrics.background = null
        lyricsView.setTextColors(color.primaryTextColor, color.secondaryTextColor)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when {
            key == SHOW_LYRICS -> {
                val enabled = sharedPreferences.getBoolean(SHOW_LYRICS, false)
                binding.root.isVisible = enabled
                if (enabled) {
                    progressViewUpdateHelper?.start()
                    updateLyrics()
                } else {
                    progressViewUpdateHelper?.stop()
                    lyricsView.clearDocument()
                }
            }
            key?.startsWith("lyrics_") == true -> {
                lyricsView.setStyle(
                    LyricsDisplaySettings.read(requireContext(), LyricsSurface.OVER_COVER)
                )
                updateLyrics()
            }
        }
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        if (PreferenceUtil.showLyrics) updateLyrics()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (PreferenceUtil.showLyrics) updateLyrics()
    }

    private fun updateLyrics() {
        val song = MusicPlayerRemote.currentSong
        lyricsLoadJob?.cancel()
        lyricsLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val translationRequested = preferences.getBoolean("lyrics_show_translation", false)
            lyricsView.setTranslationStatus(
                if (translationRequested) getString(R.string.lyrics_translation_loading) else null
            )
            val loaded = runCatching {
                LyricsDocumentLoader.load(requireContext(), song)
            }.getOrNull()

            if (_binding == null) return@launch
            lyricsView.setStyle(
                LyricsDisplaySettings.read(requireContext(), LyricsSurface.OVER_COVER)
            )
            if (loaded == null) {
                lyricsView.setTranslationStatus(null)
                lyricsView.setEmptyMessage(getString(R.string.no_lyrics_found))
            } else {
                lyricsView.setTranslationStatus(
                    if (loaded.translationFailed) getString(R.string.lyrics_translation_failed) else null
                )
                val showWordHighlight = PreferenceManager.getDefaultSharedPreferences(
                    requireContext()
                ).getBoolean("lyrics_word_highlight", true)
                lyricsView.setDocument(
                    loaded.document,
                    loaded.translations,
                    showWordHighlight
                )
            }
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        if (_binding == null) return
        lyricsView.updateTime(progress.toLong())
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        if (PreferenceUtil.showLyrics) {
            binding.root.isVisible = true
            progressViewUpdateHelper?.start()
            updateLyrics()
        }
    }

    override fun onPause() {
        super.onPause()
        progressViewUpdateHelper?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        lyricsLoadJob?.cancel()
        progressViewUpdateHelper?.stop()
        _binding = null
    }
}
