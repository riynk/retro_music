/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.fragments.player.plainatmosphere

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.NEW_BLUR_AMOUNT
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentPlainAtmospherePlayerBinding
import code.name.monkey.retromusic.extensions.whichFragment
import code.name.monkey.retromusic.fragments.base.AbsPlayerFragment
import code.name.monkey.retromusic.fragments.base.goToAlbum
import code.name.monkey.retromusic.fragments.base.goToArtist
import code.name.monkey.retromusic.fragments.player.PlayerAlbumCoverFragment
import code.name.monkey.retromusic.fragments.player.plain.PlainPlaybackControlsFragment
import code.name.monkey.retromusic.glide.BlurTransformation
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.glide.crossfadeListener
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder

private const val PLAIN_BLUR_BACKGROUND_SOURCE = "plain_blur_background_source"

class PlainAtmospherePlayerFragment : AbsPlayerFragment(R.layout.fragment_plain_atmosphere_player),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentPlainBlurPlayerBinding? = null
    private val binding get() = _binding!!
    private lateinit var controlsFragment: PlainPlaybackControlsFragment
    private var lastColor: Int = Color.WHITE
    private var lastRequest: RequestBuilder<Drawable>? = null
    private var backgroundRequest = 0

    override val paletteColor: Int
        get() = lastColor

    override fun playerToolbar(): Toolbar = binding.playerToolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlainBlurPlayerBinding.bind(view)
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
        val coverFragment: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)
        coverFragment.setCallbacks(this)

        binding.playerToolbar.apply {
            inflateMenu(R.menu.menu_plain_blur_overflow)
            setOnMenuItemClickListener(this@PlainBlurPlayerFragment)
            ToolbarContentTintHelper.colorizeToolbar(this, Color.WHITE, requireActivity())
        }
        binding.title.isSelected = true
        binding.text.isSelected = true
        binding.title.setOnClickListener { goToAlbum(requireActivity()) }
        binding.text.setOnClickListener { goToArtist(requireActivity()) }

        updateSong()
        updateBlurBackground()
    }

    private fun updateSong() {
        if (_binding == null) return
        val song = MusicPlayerRemote.currentSong
        binding.title.text = song.title
        binding.text.text = song.artistName + " • " + song.albumName
    }

    private fun updateBlurBackground() {
        if (_binding == null) return
        val song = MusicPlayerRemote.currentSong
        val requestId = ++backgroundRequest
        val source = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString(PLAIN_BLUR_BACKGROUND_SOURCE, "album_art")
        val blur = BlurTransformation.Builder(requireContext())
            .blurRadius(PreferenceUtil.blurAmount.toFloat())
            .build()

        fun display(builder: RequestBuilder<Drawable>) {
            if (_binding == null || requestId != backgroundRequest ||
                song.id != MusicPlayerRemote.currentSong.id
            ) return

            val request = builder
                .thumbnail(lastRequest)
                .error(Glide.with(this).load(ColorDrawable(Color.DKGRAY)).fitCenter())
            lastRequest = request.clone()
            request.crossfadeListener().into(binding.colorBackground)
        }

        fun loadSongArtwork() {
            display(
                Glide.with(this)
                    .load(RetroGlideExtension.getSongModel(song))
                    .simpleSongCoverOptions(song)
                    .transform(blur)
            )
        }

        if (source == "artist_image") {
            libraryViewModel.artist(song.artistId).observe(viewLifecycleOwner) { artist: Artist ->
                if (_binding == null || requestId != backgroundRequest ||
                    song.id != MusicPlayerRemote.currentSong.id
                ) return@observe

                if (artist.id == -1L) {
                    loadSongArtwork()
                } else {
                    display(
                        Glide.with(this)
                            .load(RetroGlideExtension.getArtistModel(artist))
                            .artistImageOptions(artist)
                            .transform(blur)
                    )
                }
            }
        } else {
            loadSongArtwork()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateSong()
        updateBlurBackground()
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        updateSong()
        updateBlurBackground()
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        updateSong()
        updateBlurBackground()
    }

    override fun onPause() {
        super.onPause()
        lastRequest = null
    }

    override fun onDestroyView() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        backgroundRequest++
        lastRequest = null
        super.onDestroyView()
        _binding = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == NEW_BLUR_AMOUNT || key == PLAIN_BLUR_BACKGROUND_SOURCE) {
            updateBlurBackground()
        }
    }

    override fun onColorChanged(color: MediaNotificationProcessor) {
        controlsFragment.setColor(color)
        lastColor = color.backgroundColor
        libraryViewModel.updateColor(color.backgroundColor)
        ToolbarContentTintHelper.colorizeToolbar(binding.playerToolbar, Color.WHITE, activity)
    }

    override fun toolbarIconColor(): Int = Color.WHITE

    override fun onShow() {
        controlsFragment.show()
    }

    override fun onHide() {
        controlsFragment.hide()
    }

    override fun onFavoriteToggled() {
        toggleFavorite(MusicPlayerRemote.currentSong)
    }

    override fun toggleFavorite(song: Song) {
        super.toggleFavorite(song)
        if (song.id == MusicPlayerRemote.currentSong.id) updateIsFavorite()
    }
}
