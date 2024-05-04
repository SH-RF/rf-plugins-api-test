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
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {

            List<Sequence> updatedSequences = new ArrayList<>();
            updatedSequences.addAll(this.getSequencesToDelete(request, show.get()));
            updatedSequences.addAll(this.addNewSequences(request, show.get()));
            show.get().setSequences(updatedSequences);

            List<PsaSequence> updatedPsaSequences = this.updatePsaSequences(request, show.get());
            show.get().setPsaSequences(updatedPsaSequences);
            if(updatedPsaSequences.isEmpty()) {
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
        Optional<Show> optionalShow = this.showRepository.findByShowToken(showToken);
        if(optionalShow.isPresent()) {
            Show show = optionalShow.get();
            show.setPlayingNow(request.getPlaylist());
            int sequencesPlayed = show.getPreferences().getSequencesPlayed();
            Optional<PsaSequence> psaSequence = show.getPsaSequences().stream()
                    .filter(psa -> StringUtils.equalsIgnoreCase(psa.getName(), request.getPlaylist()))
                    .findFirst();
            if(psaSequence.isPresent()) {
                sequencesPlayed = 0;
            }else {
                sequencesPlayed++;
            }
            show.getPreferences().setSequencesPlayed(sequencesPlayed);

            show.setSequences(show.getSequences().stream()
                    .peek(sequence -> {
                        if(sequence.getVisibilityCount() > 0) {
                            sequence.setVisibilityCount(sequence.getVisibilityCount() - 1);
                        }
            }).toList());

            //Managed PSA
            this.handleManagedPSA(sequencesPlayed, show);

            this.showRepository.save(show);

            return ResponseEntity.status(200).body(PluginResponse.builder().currentPlaylist(request.getPlaylist()).build());
        }
        return ResponseEntity.status(400).body(PluginResponse.builder()
                .message("Show not found")
                .build());
    }

    private void handleManagedPSA(int sequencesPlayed, Show show) {
        if(sequencesPlayed != 0 && show.getPreferences().getPsaEnabled() && show.getPreferences().getManagePsa()) {
            if(sequencesPlayed % show.getPreferences().getPsaFrequency() == 0) {
                Optional<PsaSequence> nextPsaSequence = show.getPsaSequences().stream()
                        .min(Comparator.comparing(PsaSequence::getLastPlayed)
                                .thenComparing(PsaSequence::getOrder));
                if(nextPsaSequence.isPresent()) {
                    Optional<Sequence> sequenceToAdd = show.getSequences().stream()
                            .filter(sequence -> StringUtils.equalsIgnoreCase(sequence.getName(), nextPsaSequence.get().getName()))
                            .findFirst();
                    show.getPsaSequences().get(show.getPsaSequences().indexOf(nextPsaSequence.get())).setLastPlayed(LocalDateTime.now());
                    if(show.getPreferences().getViewerControlMode() == ViewerControlMode.JUKEBOX) {
                        sequenceToAdd.ifPresent(sequence -> this.setPSASequenceRequest(show, sequence));

                    }
                }
            }
        }
    }

    private void setPSASequenceRequest(Show show, Sequence requestedSequence) {
        show.getRequests().add(Request.builder()
                .sequence(requestedSequence)
                .ownerRequested(false)
                .position(0)
                .build());
//        this.updatePlayingNext(show);
        this.showRepository.save(show);
    }

    private void updatePlayingNext(Show show) {
        Optional<Request> nextRequest = show.getRequests().stream()
                .min(Comparator.comparing(Request::getPosition));
        nextRequest.ifPresent(request -> show.setPlayingNext(request.getSequence().getName()));
    }

    public ResponseEntity<PluginResponse> updateNextScheduledSequence(UpdateNextScheduledRequest request) {
        String showToken = this.authUtil.showToken;
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
        Optional<Show> optionalShow = this.showRepository.findByShowToken(showToken);
        NextPlaylistResponse defaultResponse = NextPlaylistResponse.builder()
                .nextPlaylist(null)
                .playlistIndex(-1)
                .updateQueue(updateQueue)
                .build();
        if(optionalShow.isPresent()) {
            Show show = optionalShow.get();
            if(show.getRequests().isEmpty()) {
                return ResponseEntity.status(200).body(defaultResponse);
            }
            Optional<Request> nextRequest = show.getRequests().stream().min(Comparator.comparing(Request::getPosition));
            if(nextRequest.isEmpty()) {
                return ResponseEntity.status(200).body(defaultResponse);
            }
            this.updateVisibilityCounts(show, nextRequest.get());

            show.getRequests().remove(nextRequest.get());

//            if(show.getRequests().isEmpty()) {
//                show.setPlayingNext(null);
//            }else {
//                show.setPlayingNext(show.getRequests().get(0).getSequence().getDisplayName());
//            }

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
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        if(show.isPresent()) {
            if (show.get().getRequests().isEmpty()) {
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
        Optional<Show> show = this.showRepository.findByShowToken(showToken);
        return show.map(value -> ResponseEntity.status(200).body(RemotePreferenceResponse.builder()
                .remoteSubdomain(value.getShowSubdomain())
                .viewerControlMode(value.getPreferences().getViewerControlMode().name().toLowerCase())
                .build())).orElseGet(() -> ResponseEntity.status(400).build());
    }

    public ResponseEntity<PluginResponse> purgeQueue() {
        String showToken = this.authUtil.showToken;
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
}
