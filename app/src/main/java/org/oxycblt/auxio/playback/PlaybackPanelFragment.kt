package org.oxycblt.auxio.playback

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewTreeObserver
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.playback.ui.SwipeCoverView
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener,
    SwipeCoverView.OnSwipeListener,
    ViewTreeObserver.OnGlobalLayoutListener {

    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels() // Added MusicViewModel

    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var lastCoverWidth = 0

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // AudioEffect setup
        equalizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

        // UI setup
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        //binding.playbackToolbar.apply {
        //    setNavigationOnClickListener { playbackModel.openMain() }
        //    setOnMenuItemClickListener(this@PlaybackPanelFragment)
        //}

        binding.playbackCover.onSwipeListener = this
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar?.listener = this

        // Playback actions
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.setOnClickListener { playbackModel.togglePlaying() }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        binding.playbackMore?.setOnClickListener {
            playbackModel.song.value?.let {
                listModel.openMenu(R.menu.playback_song, it, PlaySong.ByItself)
            }
        }

        requireBinding().like?.apply {
            setOnClickListener { toggleFavorite() }
        }

        requireBinding().trash?.apply {
            setOnClickListener { toggleTrash() }
        }


        // ViewModel setup
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(playbackModel.song, ::updateLikeButton)
        collectImmediately(playbackModel.song, ::updateTrashButton)
    }

    override fun onStart() {
        super.onStart()
        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onStop() {
        super.onStop()
        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    }

    override fun onGlobalLayout() {
        if (binding == null || lastCoverWidth < 0) return
        val binding = requireBinding()
        val coverWidth = binding.playbackCover.width
        if (lastCoverWidth != coverWidth) {
            lastCoverWidth = coverWidth
        } else {
            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
            lastCoverWidth = -1
        }
    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        equalizerLauncher = null
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        //binding.playbackToolbar.setOnMenuItemClickListener(null)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean = false

    private fun toggleFavorite() {
        val currentSong = playbackModel.song.value ?: run {
            return
        }

        val likeButton = requireBinding().like
        if (musicModel.isSongInPlaylist(currentSong, "Favorites")) {
            musicModel.removeSongFromPlaylist(currentSong, "Favorites")
            likeButton?.text = "♡" // Set to unliked state
        } else {
            musicModel.addSongToPlaylist(currentSong, "Favorites")
            likeButton?.text = "♥" // Set to liked state
        }
    }

    private fun updateLikeButton(song: Song?) {
        val likeButton = requireBinding().like
        if (song == null) {
            likeButton?.isVisible = false
            return
        }
        likeButton?.isVisible = true
        val isFavorite = musicModel.isSongInPlaylist(song, "Favorites")
        likeButton?.text = if (isFavorite) "♥" else "♡"
    }

    private fun toggleTrash() {
        val currentSong = playbackModel.song.value ?: return

        val trashButton = requireBinding().trash
        if (musicModel.isSongInPlaylist(currentSong, "Trash")) {
            musicModel.removeSongFromPlaylist(currentSong, "Trash")
            trashButton?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_block_24)
        } else {
            musicModel.addSongToPlaylist(currentSong, "Trash")
            trashButton?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_unblock_24)

            playbackModel.next()
        }
    }

    private fun updateTrashButton(song: Song?) {
        val trashButton = requireBinding().trash

        if (song == null) {
            trashButton?.isVisible = false
            return
        }

        trashButton?.isVisible = true
        val isInTrash = musicModel.isSongInPlaylist(song, "Trash")

        trashButton?.icon = ContextCompat.getDrawable(
            requireContext(),
            if (isInTrash) R.drawable.ic_unblock_24 else R.drawable.ic_block_24
        )
    }


    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    override fun onSwipePrevious() {
        playbackModel.prev()
    }

    override fun onSwipeNext() {
        playbackModel.next()
    }

    private fun updateSong(song: Song?) {
        val binding = requireBinding()
        song?.let {
            binding.playbackCover.bind(it)
            binding.playbackSong.text = it.name.resolve(requireContext())
            binding.playbackArtist.text = it.artists.resolveNames(requireContext())
            binding.playbackAlbum?.text = it.album.name.resolve(requireContext())
            binding.playbackSeekBar?.durationDs = it.durationMs.msToDs()

            if (musicModel.isSongInPlaylist(it, "Trash")) {
                playbackModel.next()

                playbackModel.song.value?.let { nextSong ->
                    updateSong(nextSong)}
            }
        }
    }

    private fun updatePosition(positionDs: Long) {
        requireBinding().playbackSeekBar?.positionDs = positionDs
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        requireBinding().playbackRepeat.apply {
            setIconResource(repeatMode.icon)
            isActivated = repeatMode != RepeatMode.NONE
        }
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isActivated = isPlaying
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isActivated = isShuffled
    }

    private fun navigateToCurrentSong() {
        playbackModel.song.value?.let(detailModel::showAlbum)
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }
}
