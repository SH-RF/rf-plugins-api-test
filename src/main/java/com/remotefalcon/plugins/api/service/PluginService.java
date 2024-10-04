package com.remotefalcon.plugins.api.service;

import com.remotefalcon.library.documents.Show;
import com.remotefalcon.library.enums.ViewerControlMode;
import com.remotefalcon.library.models.*;
import com.remotefalcon.plugins.api.model.*;
import com.remotefalcon.plugins.api.repository.ShowRepository;
import com.remotefalcon.plugins.api.response.PluginResponse;
import com.remotefalcon.plugins.api.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PluginService {
    private final ShowRepository showRepository;
    private final AuthUtil authUtil;

    public ResponseEntity<PluginResponse> viewerControlMode() {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> optionalShow = this.showRepository.findByShowToken(showToken);
        if(optionalShow.isPresent()) {
            Show show = optionalShow.get();
            return ResponseEntity.status(200).body(PluginResponse.builder()
                    .viewerControlMode(show.getPreferences().getViewerControlMode().name().toLowerCase())
                    .build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    public ResponseEntity<PluginResponse> syncPlaylists(SyncPlaylistRequest request) {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {

            List<Sequence> updatedSequences = new ArrayList<>();
            updatedSequences.addAll(this.getSequencesToDelete(request, show.get()));
            updatedSequences.addAll(this.addNewSequences(request, show.get()));
            show.get().setSequences(updatedSequences);

            List<PsaSequence> updatedPsaSequences = this.updatePsaSequences(request, show.get());
            show.get().setPsaSequences(updatedPsaSequences);
            if(CollectionUtils.isEmpty(updatedPsaSequences)) {
                show.get().getPreferences().setPsaEnabled(false);
            }

            this.showRepository.save(show.get());
            return ResponseEntity.status(200).body(PluginResponse.builder().message("Success").build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    private List<Sequence> getSequencesToDelete(SyncPlaylistRequest request, Show show) {
        List<String> playlistNamesInRequest = request.getPlaylists().stream().map(SyncPlaylistDetails::getPlaylistName).toList();
        List<Sequence> sequencesToDelete = new ArrayList<>();
        int inactiveSequenceOrder = request.getPlaylists().size() + 1;
        for(Sequence existingSequence : show.getSequences()) {
            if(!playlistNamesInRequest.contains(existingSequence.getName())) {
                existingSequence.setActive(false);
                existingSequence.setIndex(null);
                existingSequence.setOrder(inactiveSequenceOrder);
                sequencesToDelete.add(existingSequence);
                inactiveSequenceOrder++;
            }
        }
        return sequencesToDelete;
    }

    private List<Sequence> addNewSequences(SyncPlaylistRequest request, Show show) {
        Set<String> existingSequences = show.getSequences().stream().map(Sequence::getName).collect(Collectors.toSet());
        List<Sequence> sequencesToSync = new ArrayList<>();
        Optional<Sequence> lastSequenceInOrder = show.getSequences().stream()
                .filter(Sequence::getActive)
                .max(Comparator.comparing(Sequence::getOrder));
        int sequenceOrder = 0;
        if(lastSequenceInOrder.isPresent()) {
            sequenceOrder = lastSequenceInOrder.get().getOrder();
        }
        AtomicInteger atomicSequenceOrder = new AtomicInteger(sequenceOrder);
        for(SyncPlaylistDetails playlistInRequest : request.getPlaylists()) {
            if(!existingSequences.contains(playlistInRequest.getPlaylistName())) {
                sequencesToSync.add(Sequence.builder()
                        .active(true)
                        .displayName(playlistInRequest.getPlaylistName())
                        .duration(playlistInRequest.getPlaylistDuration())
                        .imageUrl("")
                        .index(playlistInRequest.getPlaylistIndex() != null ? playlistInRequest.getPlaylistIndex() : -1)
                        .name(playlistInRequest.getPlaylistName())
                        .order(atomicSequenceOrder.get())
                        .visible(true)
                        .visibilityCount(0)
                        .type(playlistInRequest.getPlaylistType() == null ? "SEQUENCE" : playlistInRequest.getPlaylistType())
                        .build());
                atomicSequenceOrder.getAndIncrement();
            }else {
                show.getSequences().forEach(sequence -> {
                    if(StringUtils.equalsIgnoreCase(sequence.getName(), playlistInRequest.getPlaylistName())) {
                        sequence.setIndex(playlistInRequest.getPlaylistIndex() != null ? playlistInRequest.getPlaylistIndex() : -1);
                        sequence.setActive(true);
                        sequencesToSync.add(sequence);
                    }
                });
            }
        }
        return sequencesToSync;
    }

    private List<PsaSequence> updatePsaSequences(SyncPlaylistRequest request, Show show) {
        List<PsaSequence> updatedPsaSequences = new ArrayList<>();
        List<String> playlistNamesInRequest = request.getPlaylists().stream().map(SyncPlaylistDetails::getPlaylistName).toList();
        if(CollectionUtils.isNotEmpty(show.getPsaSequences())) {
            for(PsaSequence psa : show.getPsaSequences()) {
                if(playlistNamesInRequest.contains(psa.getName())) {
                    updatedPsaSequences.add(psa);
                }
            }
        }
        return updatedPsaSequences;
    }

    public ResponseEntity<PluginResponse> updateWhatsPlaying(UpdateWhatsPlayingRequest request) {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> optionalShow = this.showRepository.findByShowToken(showToken);
        if(optionalShow.isPresent()) {
            Show show = optionalShow.get();
            show.setPlayingNow(request.getPlaylist());
            int sequencesPlayed = show.getPreferences().getSequencesPlayed();
            Optional<Sequence> whatsPlayingSequence = show.getSequences().stream()
                    .filter(sequence -> StringUtils.equalsIgnoreCase(sequence.getName(), request.getPlaylist()))
                    .findFirst();
            Optional<PsaSequence> psaSequence = show.getPsaSequences().stream()
                    .filter(psa -> StringUtils.equalsIgnoreCase(psa.getName(), request.getPlaylist()))
                    .findFirst();
            if(psaSequence.isPresent()) {
                sequencesPlayed = 0;
            }else {
                sequencesPlayed++;
            }
            if(whatsPlayingSequence.isPresent() && StringUtils.isNotEmpty(whatsPlayingSequence.get().getGroup())) {
                sequencesPlayed--;
            }
            show.getPreferences().setSequencesPlayed(sequencesPlayed);

            show.setSequences(show.getSequences().stream()
                    .peek(sequence -> {
                        if(sequence.getVisibilityCount() > 0) {
                            sequence.setVisibilityCount(sequence.getVisibilityCount() - 1);
                        }
            }).toList());

            show.setSequenceGroups(show.getSequenceGroups().stream()
                    .peek(sequenceGroup -> {
                        if(sequenceGroup.getVisibilityCount() > 0) {
                            sequenceGroup.setVisibilityCount(sequenceGroup.getVisibilityCount() - 1);
                        }
                    }).toList());

            //Managed PSA
            this.handleManagedPSA(sequencesPlayed, show);

            this.clearViewersVotedAndRequested(show);

            this.showRepository.save(show);

            return ResponseEntity.status(200).body(PluginResponse.builder().currentPlaylist(request.getPlaylist()).build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    private void clearViewersVotedAndRequested(Show show) {
        if(CollectionUtils.isNotEmpty(show.getRequests())) {
            show.getRequests().forEach(request -> request.setViewerRequested(null));
        }
        if(CollectionUtils.isNotEmpty(show.getVotes())) {
            show.getVotes().forEach(vote -> vote.setViewersVoted(new ArrayList<>()));
        }
    }

    private void handleManagedPSA(int sequencesPlayed, Show show) {
        if(sequencesPlayed != 0 && show.getPreferences().getPsaEnabled() && show.getPreferences().getManagePsa() && show.getPreferences().getPsaFrequency() > 0) {
            if(sequencesPlayed % show.getPreferences().getPsaFrequency() == 0) {
                Optional<PsaSequence> nextPsaSequence = show.getPsaSequences().stream()
                        .min(Comparator.comparing(PsaSequence::getLastPlayed)
                                .thenComparing(PsaSequence::getOrder));
                boolean isPSAPlayingNow = show.getPsaSequences().stream()
                        .anyMatch(psaSequence -> StringUtils.equalsIgnoreCase(show.getPlayingNow(), psaSequence.getName()));
                if(nextPsaSequence.isPresent() && !isPSAPlayingNow) {
                    Optional<Sequence> sequenceToAdd = show.getSequences().stream()
                            .filter(sequence -> StringUtils.equalsIgnoreCase(sequence.getName(), nextPsaSequence.get().getName()))
                            .findFirst();
                    show.getPsaSequences().get(show.getPsaSequences().indexOf(nextPsaSequence.get())).setLastPlayed(LocalDateTime.now());
                    if(show.getPreferences().getViewerControlMode() == ViewerControlMode.JUKEBOX) {
                        sequenceToAdd.ifPresent(sequence -> this.setPSASequenceRequest(show, sequence));
                    }else if(show.getPreferences().getViewerControlMode() == ViewerControlMode.VOTING) {
                        sequenceToAdd.ifPresent(sequence -> this.setPSASequenceVote(show, sequence));
                    }
                }
            }
        }
    }

    private void setPSASequenceRequest(Show show, Sequence requestedSequence) {
        List<String> psaSequences = show.getPsaSequences().stream().map(PsaSequence::getName).toList();
        boolean isPsaInJukebox = show.getRequests().stream().anyMatch(request -> psaSequences.contains(request.getSequence().getName()));
        if(!isPsaInJukebox) {
            show.getVotes().add(Vote.builder()
                    .sequence(requestedSequence)
                    .ownerVoted(false)
                    .lastVoteTime(LocalDateTime.now())
                    .votes(2000)
                    .build());
            this.showRepository.save(show);
        }
        show.getRequests().add(Request.builder()
                .sequence(requestedSequence)
                .ownerRequested(false)
                .position(0)
                .build());
        this.showRepository.save(show);
    }

    private void setPSASequenceVote(Show show, Sequence requestedSequence) {
        List<String> psaSequences = show.getPsaSequences().stream().map(PsaSequence::getName).toList();
        boolean isPsaInVotes = show.getVotes().stream().anyMatch(vote -> psaSequences.contains(vote.getSequence().getName()));
        if(!isPsaInVotes) {
            show.getVotes().add(Vote.builder()
                    .sequence(requestedSequence)
                    .ownerVoted(false)
                    .lastVoteTime(LocalDateTime.now())
                    .votes(2000)
                    .build());
            this.showRepository.save(show);
        }
    }

    public ResponseEntity<PluginResponse> updateNextScheduledSequence(UpdateNextScheduledRequest request) {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> optionalShow = this.showRepository.findByShowToken(showToken);
        if(optionalShow.isPresent()) {
            Show show = optionalShow.get();
            show.setPlayingNextFromSchedule(request.getSequence());
            this.showRepository.save(show);
            return ResponseEntity.status(200).body(PluginResponse.builder().currentPlaylist(request.getSequence()).build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    public ResponseEntity<NextPlaylistResponse> nextPlaylistInQueue(Boolean updateQueue) {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> optionalShow = this.showRepository.findByShowToken(showToken);
        NextPlaylistResponse defaultResponse = NextPlaylistResponse.builder()
                .nextPlaylist(null)
                .playlistIndex(-1)
                .updateQueue(updateQueue)
                .build();
        if(optionalShow.isPresent()) {
            Show show = optionalShow.get();
            if(CollectionUtils.isEmpty(show.getRequests())) {
                return ResponseEntity.status(200).body(defaultResponse);
            }
            Optional<Request> nextRequest = show.getRequests().stream().min(Comparator.comparing(Request::getPosition));
            if(nextRequest.isEmpty()) {
                return ResponseEntity.status(200).body(defaultResponse);
            }
            this.updateVisibilityCounts(show, nextRequest.get());

            show.getRequests().remove(nextRequest.get());

            this.showRepository.save(show);

            return ResponseEntity.status(200).body(NextPlaylistResponse.builder()
                    .nextPlaylist(nextRequest.get().getSequence().getName())
                    .playlistIndex(nextRequest.get().getSequence().getIndex())
                    .updateQueue(updateQueue)
                    .build());
        }
        return ResponseEntity.status(400).body(NextPlaylistResponse.builder()
                .nextPlaylist(null)
                .playlistIndex(-1)
                .updateQueue(updateQueue)
                .build());
    }

    public ResponseEntity<HighestVotedPlaylistResponse> highestVotedPlaylist() {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> optionalShow = this.showRepository.findByShowToken(showToken);
        HighestVotedPlaylistResponse response = HighestVotedPlaylistResponse.builder()
                .winningPlaylist(null)
                .playlistIndex(-1)
                .build();
        if(optionalShow.isPresent()) {
            Show show = optionalShow.get();

            //Update visibility counts
            List<Sequence> sequences = show.getSequences().stream().peek(sequence -> {
                if(sequence.getVisibilityCount() > 0) {
                    sequence.setVisibilityCount(sequence.getVisibilityCount() - 1);
                }
            }).toList();
            List<SequenceGroup> sequenceGroups = show.getSequenceGroups().stream().peek(sequenceGroup -> {
                if(sequenceGroup.getVisibilityCount() > 0) {
                    sequenceGroup.setVisibilityCount(sequenceGroup.getVisibilityCount() - 1);
                }
            }).toList();

            show.setSequences(sequences);
            show.setSequenceGroups(sequenceGroups);

            //Get the sequence with the most votes. If there is a tie, get the sequence with the earliest vote time
            if(CollectionUtils.isNotEmpty(show.getVotes())) {
                Optional<Vote> winningVote = show.getVotes().stream()
                        .max(Comparator.comparing(Vote::getVotes)
                                .thenComparing(Comparator.comparing(Vote::getLastVoteTime).reversed()));
                if(winningVote.isPresent()) {
                    SequenceGroup winningSequenceGroup = winningVote.get().getSequenceGroup();
                    if(winningSequenceGroup != null) {
                        return ResponseEntity.status(200).body(this.processWinningGroup(winningVote.get(), show));
                    }else {
                        return ResponseEntity.status(200).body(this.processWinningVote(winningVote.get(), show));
                    }
                }
            }
            this.showRepository.save(show);
            return ResponseEntity.status(200).body(response);
        }
        return ResponseEntity.status(400).body(response);
    }

    private HighestVotedPlaylistResponse processWinningGroup(Vote winningVote, Show show) {
        SequenceGroup winningSequenceGroup = winningVote.getSequenceGroup();
        show.getVotes().remove(winningVote);

        if(winningSequenceGroup != null) {
            Optional<SequenceGroup> actualSequenceGroup = show.getSequenceGroups().stream()
                    .filter(sequenceGroup -> StringUtils.equalsIgnoreCase(sequenceGroup.getName(), winningSequenceGroup.getName()))
                    .findFirst();

            if(actualSequenceGroup.isPresent()) {
                show.getStats().getVotingWin().add(Stat.VotingWin.builder()
                        .name(actualSequenceGroup.get().getName())
                        .dateTime(LocalDateTime.now())
                        .build());

                //Set visibility counts
                if(show.getPreferences().getHideSequenceCount() != 0) {
                    actualSequenceGroup.get().setVisibilityCount(show.getPreferences().getHideSequenceCount());
                }

                List<Sequence> sequencesInGroup = new ArrayList<>(show.getSequences().stream()
                        .filter(sequence -> StringUtils.equalsIgnoreCase(actualSequenceGroup.get().getName(), sequence.getGroup()))
                        .toList());

                int voteCount = 2099;

                Vote updatedWinningVote = Vote.builder()
                        .votes(voteCount)
                        .lastVoteTime(LocalDateTime.now())
                        .ownerVoted(false)
                        .sequence(sequencesInGroup.get(0))
                        .build();
                voteCount--;

                sequencesInGroup.remove(0);

                List<Vote> sequencesInGroupVotes = new ArrayList<>();
                for(Sequence groupedSequence : sequencesInGroup) {
                    sequencesInGroupVotes.add(Vote.builder()
                            .votes(voteCount)
                            .lastVoteTime(LocalDateTime.now())
                            .ownerVoted(false)
                            .sequence(groupedSequence)
                            .build());
                    voteCount--;
                }
                show.getVotes().addAll(sequencesInGroupVotes);
                return this.processWinningVote(updatedWinningVote, show);
            }
        }
        return null;
    }

    private HighestVotedPlaylistResponse processWinningVote(Vote winningVote, Show show) {
        Sequence winningSequence = winningVote.getSequence();
        show.getVotes().remove(winningVote);

        if(winningSequence != null) {
            boolean winningSequenceIsPSA = show.getPsaSequences().stream()
                    .anyMatch(psaSequence -> StringUtils.equalsIgnoreCase(psaSequence.getName(), winningSequence.getName()));
            Optional<Sequence> actualSequence = show.getSequences().stream()
                    .filter(sequence -> StringUtils.equalsIgnoreCase(sequence.getName(), winningSequence.getName()))
                    .findFirst();

            if(actualSequence.isPresent()) {
                boolean noGroupedSequencesHaveVotes = show.getVotes().stream()
                        .noneMatch(vote -> vote.getSequence() == null || StringUtils.isNotEmpty(vote.getSequence().getGroup()));

                //Vote resets should only happen if there are no grouped sequences with active votes
                if(noGroupedSequencesHaveVotes) {
                    //Reset votes
                    if(show.getPreferences().getResetVotes()) {
                        show.getVotes().clear();
                    }
                }

                //Set visibility counts
                if(show.getPreferences().getHideSequenceCount() != 0 && StringUtils.isEmpty(actualSequence.get().getGroup())) {
                    actualSequence.get().setVisibilityCount(show.getPreferences().getHideSequenceCount());
                }

                //Only save stats for non-grouped sequences
                if(StringUtils.isEmpty(actualSequence.get().getGroup()) && !winningSequenceIsPSA) {
                    show.getStats().getVotingWin().add(Stat.VotingWin.builder()
                            .name(actualSequence.get().getName())
                            .dateTime(LocalDateTime.now())
                            .build());
                }

                if(show.getPreferences().getPsaEnabled() && !show.getPreferences().getManagePsa()
                   && CollectionUtils.isNotEmpty(show.getPsaSequences()) && StringUtils.isEmpty(actualSequence.get().getGroup()) && !winningSequenceIsPSA) {
                    Integer voteWinsToday = show.getStats().getVotingWin().stream()
                            .filter(stat -> stat.getDateTime().isAfter(LocalDateTime.now().withHour(0).withMinute(0).withSecond(0)))
                            .toList()
                            .size();
                    boolean isPSAPlayingNow = show.getPsaSequences().stream()
                            .anyMatch(psaSequence -> StringUtils.equalsIgnoreCase(show.getPlayingNow(), psaSequence.getName()));
                    if(voteWinsToday % show.getPreferences().getPsaFrequency() == 0 && !isPSAPlayingNow) {
                        Optional<PsaSequence> nextPsaSequence = show.getPsaSequences().stream()
                                .min(Comparator.comparing(PsaSequence::getLastPlayed)
                                        .thenComparing(PsaSequence::getOrder));
                        if(nextPsaSequence.isPresent()) {
                            Optional<Sequence> sequenceToAdd = show.getSequences().stream()
                                    .filter(sequence -> StringUtils.equalsIgnoreCase(sequence.getName(), nextPsaSequence.get().getName()))
                                    .findFirst();
                            show.getPsaSequences().get(show.getPsaSequences().indexOf(nextPsaSequence.get())).setLastPlayed(LocalDateTime.now());
                            //Final Sanity check
                            List<String> psaSequences = show.getPsaSequences().stream().map(PsaSequence::getName).toList();
                            boolean isPsaInVotes = show.getVotes().stream().anyMatch(vote -> psaSequences.contains(vote.getSequence().getName()));
                            if(!isPsaInVotes) {
                                sequenceToAdd.ifPresent(sequence -> show.getVotes().add(Vote.builder()
                                        .sequence(sequence)
                                        .ownerVoted(false)
                                        .lastVoteTime(LocalDateTime.now())
                                        .votes(2000)
                                        .build()));
                            }
                        }
                    }
                }

                this.showRepository.save(show);

                //Return winning sequence
                return HighestVotedPlaylistResponse.builder()
                        .winningPlaylist(actualSequence.get().getName())
                        .playlistIndex(actualSequence.get().getIndex())
                        .build();
            }
        }
        return null;
    }

    private void updateVisibilityCounts(Show show, Request request) {
        if(show.getPreferences().getHideSequenceCount() != 0) {
            if(!StringUtils.isEmpty(request.getSequence().getGroup())) {
                Optional<SequenceGroup> sequenceGroup = show.getSequenceGroups().stream()
                        .filter((group) -> StringUtils.equalsIgnoreCase(group.getName(), request.getSequence().getGroup()))
                        .findFirst();
                sequenceGroup.ifPresent(group -> group.setVisibilityCount(show.getPreferences().getHideSequenceCount() + 1));
            }else {
                Optional<Sequence> sequence = show.getSequences().stream()
                        .filter((seq) -> StringUtils.equalsIgnoreCase(seq.getName(), request.getSequence().getName()))
                        .findFirst();
                sequence.ifPresent(seq -> seq.setVisibilityCount(show.getPreferences().getHideSequenceCount() + 1));
            }
        }
    }

    public ResponseEntity<PluginResponse> updatePlaylistQueue() {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {
            if (CollectionUtils.isEmpty(show.get().getRequests())) {
                return ResponseEntity.status(200).body(PluginResponse.builder().message("Queue Empty").build());
            } else {
                return ResponseEntity.status(200).body(PluginResponse.builder().message("Success").build());
            }
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    public ResponseEntity<PluginResponse> pluginVersion(PluginVersion request) {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {
            show.get().setPluginVersion(request.getPluginVersion());
            show.get().setFppVersion(request.getFppVersion());
            this.showRepository.save(show.get());
            return ResponseEntity.status(200).body(PluginResponse.builder().message("Success").build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    public ResponseEntity<RemotePreferenceResponse> remotePreferences() {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        return show.map(value -> ResponseEntity.status(200).body(RemotePreferenceResponse.builder()
                .remoteSubdomain(value.getShowSubdomain())
                .viewerControlMode(value.getPreferences().getViewerControlMode().name().toLowerCase())
                .build())).orElseGet(() -> ResponseEntity.status(400).build());
    }

    public ResponseEntity<PluginResponse> purgeQueue() {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {
            show.get().setRequests(new ArrayList<>());
            this.showRepository.save(show.get());
            return ResponseEntity.status(200).body(PluginResponse.builder().message("Success").build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    public ResponseEntity<PluginResponse> resetAllVotes() {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {
            show.get().setVotes(new ArrayList<>());
            this.showRepository.save(show.get());
            return ResponseEntity.status(200).body(PluginResponse.builder().message("Success").build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    public ResponseEntity<PluginResponse> toggleViewerControl() {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {
            show.get().getPreferences().setViewerControlEnabled(!show.get().getPreferences().getViewerControlEnabled());
            this.showRepository.save(show.get());
            return ResponseEntity.status(200).body(PluginResponse.builder().viewerControlEnabled(!show.get().getPreferences().getViewerControlEnabled()).build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    public ResponseEntity<PluginResponse> updateViewerControl(ViewerControlRequest request) {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {
            show.get().getPreferences().setViewerControlEnabled(StringUtils.equalsIgnoreCase("Y", request.getViewerControlEnabled()));
            this.showRepository.save(show.get());
            return ResponseEntity.status(200).body(PluginResponse.builder().viewerControlEnabled(StringUtils.equalsIgnoreCase("Y", request.getViewerControlEnabled())).build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    public ResponseEntity<PluginResponse> updateManagedPsa(ManagedPSARequest request) {
        String showToken = this.authUtil.showToken;
        if(showToken == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {
            show.get().getPreferences().setManagePsa(StringUtils.equalsIgnoreCase("Y", request.getManagedPsaEnabled()));
            this.showRepository.save(show.get());
            return ResponseEntity.status(200).body(PluginResponse.builder().managedPsaEnabled(StringUtils.equalsIgnoreCase("Y", request.getManagedPsaEnabled())).build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }
}
