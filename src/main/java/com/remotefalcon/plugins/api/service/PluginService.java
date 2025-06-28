package com.remotefalcon.plugins.api.service;

import com.remotefalcon.library.enums.ViewerControlMode;
import com.remotefalcon.library.models.*;
import com.remotefalcon.library.quarkus.entity.Show;
import com.remotefalcon.plugins.api.context.ShowContext;
import com.remotefalcon.plugins.api.model.*;
import com.remotefalcon.plugins.api.repository.ShowRepository;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

@RequestScoped
public class PluginService {

    @Inject
    ShowContext showContext;

    @Inject
    ShowRepository showRepository;

    @Inject
    @ConfigProperty(name = "sequence.limit")
    int sequenceLimit;

    public NextPlaylistResponse nextPlaylistInQueue() {
        Show show = showContext.getShow();
        NextPlaylistResponse defaultResponse = NextPlaylistResponse.builder()
                .nextPlaylist(null)
                .playlistIndex(-1)
                .build();
        if(CollectionUtils.isEmpty(show.getRequests())) {
            return defaultResponse;
        }
        Optional<Request> nextRequest = show.getRequests().stream().min(Comparator.comparing(Request::getPosition));
        if(nextRequest.isEmpty()) {
            return defaultResponse;
        }
        this.updateVisibilityCounts(show, nextRequest.get());

        show.getRequests().remove(nextRequest.get());

        this.showRepository.persistOrUpdate(show);

        return NextPlaylistResponse.builder()
                .nextPlaylist(nextRequest.get().getSequence().getName())
                .playlistIndex(nextRequest.get().getSequence().getIndex())
                .build();
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

    public PluginResponse updatePlaylistQueue() {
        Show show = showContext.getShow();
        if (CollectionUtils.isEmpty(show.getRequests())) {
            return PluginResponse.builder().message("Queue Empty").build();
        } else {
            return PluginResponse.builder().message("Success").build();
        }
    }

    public PluginResponse syncPlaylists(SyncPlaylistRequest request) {
        Show show = showContext.getShow();
        if (request.getPlaylists().size() > this.sequenceLimit) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(PluginResponse.builder().message("Cannot sync more than " + this.sequenceLimit + " sequences").build())
                            .build()
            );
        }
        Set<Sequence> updatedSequences = new HashSet<>();
        updatedSequences.addAll(this.getSequencesToDelete(request, show));
        updatedSequences.addAll(this.addNewSequences(request, show));
        show.setSequences(updatedSequences.stream().toList());

        List<PsaSequence> updatedPsaSequences = this.updatePsaSequences(request, show);
        show.setPsaSequences(updatedPsaSequences);
        if(CollectionUtils.isEmpty(updatedPsaSequences)) {
            show.getPreferences().setPsaEnabled(false);
        }

        this.showRepository.persistOrUpdate(show);
        return PluginResponse.builder().message("Success").build();
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

    public PluginResponse updateWhatsPlaying(UpdateWhatsPlayingRequest request) {
        if(request == null || StringUtils.isEmpty(request.getPlaylist())) {
            return PluginResponse.builder().build();
        }
        Show show = showContext.getShow();
        if(show.getPreferences() == null) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(PluginResponse.builder().message("Preferences not found").build())
                            .build()
            );
        }
        if(StringUtils.isEmpty(request.getPlaylist())) {
            show.setPlayingNow("");
            show.setPlayingNext("");
            show.setPlayingNextFromSchedule("");
            return PluginResponse.builder().currentPlaylist(request.getPlaylist()).build();
        }else {
            show.setPlayingNow(request.getPlaylist());
        }
        int sequencesPlayed = show.getPreferences().getSequencesPlayed() != null ? show.getPreferences().getSequencesPlayed() : 0;
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

        Set<Sequence> sequenceSet = show.getSequences().stream()
                .peek(sequence -> {
                    if(sequence.getVisibilityCount() > 0) {
                        sequence.setVisibilityCount(sequence.getVisibilityCount() - 1);
                    }
                }).collect(Collectors.toSet());

        show.setSequences(sequenceSet.stream().toList());

        show.setSequenceGroups(show.getSequenceGroups().stream()
                .peek(sequenceGroup -> {
                    if(sequenceGroup.getVisibilityCount() > 0) {
                        sequenceGroup.setVisibilityCount(sequenceGroup.getVisibilityCount() - 1);
                    }
                }).toList());

        //Managed PSA
        this.handleManagedPSA(sequencesPlayed, show);

        this.clearViewersVotedAndRequested(show);

        this.showRepository.persistOrUpdate(show);

        return PluginResponse.builder().currentPlaylist(request.getPlaylist()).build();
    }

    private void handleManagedPSA(int sequencesPlayed, Show show) {
        if(show.getPsaSequences() != null && !show.getPsaSequences().isEmpty()) {
            if(sequencesPlayed != 0 && show.getPreferences().getPsaEnabled() && show.getPreferences().getManagePsa()
                    && show.getPreferences().getPsaFrequency() != null && show.getPreferences().getPsaFrequency() > 0) {
                if(sequencesPlayed % show.getPreferences().getPsaFrequency() == 0) {
                    Optional<PsaSequence> nextPsaSequence = show.getPsaSequences().stream()
                            .filter(Objects::nonNull)
                            .filter(psaSequence -> psaSequence.getLastPlayed() != null)
                            .filter(psaSequence -> psaSequence.getOrder() != null)
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
            this.showRepository.persistOrUpdate(show);
        }
        show.getRequests().add(Request.builder()
                .sequence(requestedSequence)
                .ownerRequested(false)
                .position(0)
                .build());
        this.showRepository.persistOrUpdate(show);
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
            this.showRepository.persistOrUpdate(show);
        }
    }

    private void clearViewersVotedAndRequested(Show show) {
        if(CollectionUtils.isNotEmpty(show.getRequests())) {
            show.getRequests().forEach(request -> request.setViewerRequested(null));
        }
        if(CollectionUtils.isNotEmpty(show.getVotes())) {
            show.getVotes().forEach(vote -> vote.setViewersVoted(new ArrayList<>()));
        }
    }

    public PluginResponse updateNextScheduledSequence(UpdateNextScheduledRequest request) {
        Show show = showContext.getShow();
        if(show.getPreferences() == null) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(PluginResponse.builder().message("Preferences not found").build())
                            .build()
            );
        }
        if(StringUtils.isEmpty(request.getSequence())) {
            show.setPlayingNow("");
            show.setPlayingNext("");
            show.setPlayingNextFromSchedule("");
        }else {
            show.setPlayingNextFromSchedule(request.getSequence());
        }
        this.showRepository.persistOrUpdate(show);
        return PluginResponse.builder().nextScheduledSequence(request.getSequence()).build();
    }

    public PluginResponse viewerControlMode() {
        Show show = showContext.getShow();
        String viewerControlMode = show.getPreferences().getViewerControlMode().name().toLowerCase();
        return PluginResponse.builder()
                .viewerControlMode(viewerControlMode)
                .build();
    }

    public HighestVotedPlaylistResponse highestVotedPlaylist() {
        Show show = showContext.getShow();
        HighestVotedPlaylistResponse response = HighestVotedPlaylistResponse.builder()
                .winningPlaylist(null)
                .playlistIndex(-1)
                .build();
        //Get the sequence with the most votes. If there is a tie, get the sequence with the earliest vote time
        if(CollectionUtils.isNotEmpty(show.getVotes())) {
            Optional<Vote> winningVote = show.getVotes().stream()
                    .max(Comparator.comparing(Vote::getVotes)
                            .thenComparing(Comparator.comparing(Vote::getLastVoteTime).reversed()));
            if(winningVote.isPresent()) {
                SequenceGroup winningSequenceGroup = winningVote.get().getSequenceGroup();
                if(winningSequenceGroup != null) {
                    return this.processWinningGroup(winningVote.get(), show);
                }else {
                    return this.processWinningVote(winningVote.get(), show);
                }
            }
        }
        this.showRepository.persistOrUpdate(show);

        return response;
    }

    private HighestVotedPlaylistResponse processWinningGroup(Vote winningVote, Show show) {
        SequenceGroup winningSequenceGroup = winningVote.getSequenceGroup();
        show.getVotes().remove(winningVote);

        if(winningSequenceGroup != null) {
            Optional<SequenceGroup> actualSequenceGroup = show.getSequenceGroups().stream()
                    .filter(sequenceGroup -> StringUtils.equalsIgnoreCase(sequenceGroup.getName(), winningSequenceGroup.getName()))
                    .findFirst();

            if(actualSequenceGroup.isPresent()) {
                List<Sequence> sequencesInGroup = new ArrayList<>(show.getSequences().stream()
                        .filter(sequence -> StringUtils.equalsIgnoreCase(actualSequenceGroup.get().getName(), sequence.getGroup()))
                        .toList());
                if(CollectionUtils.isEmpty(sequencesInGroup)) {
                    return null;
                }

                show.getStats().getVotingWin().add(Stat.VotingWin.builder()
                        .name(actualSequenceGroup.get().getName())
                        .dateTime(LocalDateTime.now())
                        .build());

                //Set visibility counts
                if(show.getPreferences().getHideSequenceCount() != 0) {
                    actualSequenceGroup.get().setVisibilityCount(show.getPreferences().getHideSequenceCount() + 1);
                }

                int voteCount = 2099;

                Vote updatedWinningVote = Vote.builder()
                        .votes(voteCount)
                        .lastVoteTime(LocalDateTime.now())
                        .ownerVoted(false)
                        .sequence(sequencesInGroup.getFirst())
                        .build();
                voteCount--;

                sequencesInGroup.removeFirst();

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
                    actualSequence.get().setVisibilityCount(show.getPreferences().getHideSequenceCount() + 1);
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
                                .filter(Objects::nonNull)
                                .filter(psaSequence -> psaSequence.getLastPlayed() != null)
                                .filter(psaSequence -> psaSequence.getOrder() != null)
                                .min(Comparator.comparing(PsaSequence::getLastPlayed)
                                        .thenComparing(PsaSequence::getOrder));
                        if(nextPsaSequence.isPresent()) {
                            Optional<Sequence> sequenceToAdd = show.getSequences().stream()
                                    .filter(sequence -> StringUtils.equalsIgnoreCase(sequence.getName(), nextPsaSequence.get().getName()))
                                    .findFirst();
                            show.getPsaSequences().get(show.getPsaSequences().indexOf(nextPsaSequence.get())).setLastPlayed(LocalDateTime.now());
                            //Final Sanity check
                            List<String> psaSequences = show.getPsaSequences().stream().map(PsaSequence::getName).toList();
                            boolean isPsaInVotes = show.getVotes().stream().anyMatch(vote -> (vote.getSequence() != null
                                    && vote.getSequence().getName() != null
                                    && psaSequences.contains(vote.getSequence().getName())));
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

                this.showRepository.persistOrUpdate(show);

                //Return winning sequence
                return HighestVotedPlaylistResponse.builder()
                        .winningPlaylist(actualSequence.get().getName())
                        .playlistIndex(actualSequence.get().getIndex())
                        .build();
            }
        }
        return null;
    }

    public PluginResponse pluginVersion(PluginVersion request) {
        Show show = showContext.getShow();
        show.setPluginVersion(request.getPluginVersion());
        show.setFppVersion(request.getFppVersion());
        this.showRepository.persistOrUpdate(show);
        return PluginResponse.builder().message("Success").build();
    }

    public RemotePreferenceResponse remotePreferences() {
        Show show = showContext.getShow();
        return RemotePreferenceResponse.builder()
                .remoteSubdomain(show.getShowSubdomain())
                .viewerControlMode(show.getPreferences().getViewerControlMode().name().toLowerCase())
                .build();
    }

    public PluginResponse purgeQueue() {
        Show show = showContext.getShow();
        show.setRequests(new ArrayList<>());
        show.setVotes(new ArrayList<>());
        this.showRepository.persistOrUpdate(show);
        return PluginResponse.builder().message("Success").build();
    }

    public PluginResponse resetAllVotes() {
        Show show = showContext.getShow();
        show.setVotes(new ArrayList<>());
        this.showRepository.persistOrUpdate(show);
        return PluginResponse.builder().message("Success").build();
    }

    public PluginResponse toggleViewerControl() {
        Show show = showContext.getShow();
        show.getPreferences().setViewerControlEnabled(!show.getPreferences().getViewerControlEnabled());
        show.getPreferences().setSequencesPlayed(0);
        this.showRepository.persistOrUpdate(show);
        return PluginResponse.builder().viewerControlEnabled(!show.getPreferences().getViewerControlEnabled()).build();
    }

    public PluginResponse updateViewerControl(ViewerControlRequest request) {
        Show show = showContext.getShow();
        if(show.getPreferences() == null) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(PluginResponse.builder().message("Preferences not found").build())
                            .build()
            );
        }
        show.getPreferences().setViewerControlEnabled(StringUtils.equalsIgnoreCase("Y", request.getViewerControlEnabled())); //HERE
        this.showRepository.persistOrUpdate(show);
        return PluginResponse.builder().viewerControlEnabled(StringUtils.equalsIgnoreCase("Y", request.getViewerControlEnabled())).build();
    }

    public PluginResponse updateManagedPsa(ManagedPSARequest request) {
        Show show = showContext.getShow();
        if(show.getPreferences() == null) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(PluginResponse.builder().message("Preferences not found").build())
                            .build()
            );
        }
        show.getPreferences().setManagePsa(StringUtils.equalsIgnoreCase("Y", request.getManagedPsaEnabled()));
        this.showRepository.persistOrUpdate(show);
        return PluginResponse.builder().managedPsaEnabled(StringUtils.equalsIgnoreCase("Y", request.getManagedPsaEnabled())).build();
    }

    public void fppHeartbeat() {
        Show show = showContext.getShow();
        show.setLastFppHeartbeat(LocalDateTime.now());
        this.showRepository.persistOrUpdate(show);
    }
}
