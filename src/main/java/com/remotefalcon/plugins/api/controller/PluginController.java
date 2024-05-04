package com.remotefalcon.plugins.api.controller;

import com.remotefalcon.plugins.api.aop.RequiresAccess;
import com.remotefalcon.plugins.api.model.*;
import com.remotefalcon.plugins.api.response.PluginResponse;
import com.remotefalcon.plugins.api.service.PluginService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PluginController {
  private final PluginService pluginService;

  @GetMapping(value = "/nextPlaylistInQueue")
  @RequiresAccess
  public ResponseEntity<NextPlaylistResponse> nextPlaylistInQueue(@RequestParam(name = "updateQueue", required = false) Boolean updateQueue) {
    return this.pluginService.nextPlaylistInQueue(updateQueue);
  }

  @PostMapping(value = "/updatePlaylistQueue")
  @RequiresAccess
  public ResponseEntity<PluginResponse> updatePlaylistQueue() {
    return this.pluginService.updatePlaylistQueue();
  }

  @PostMapping(value = "/syncPlaylists")
  @RequiresAccess
  public ResponseEntity<PluginResponse> syncPlaylists(@RequestBody SyncPlaylistRequest request) {
    return this.pluginService.syncPlaylists(request);
  }

  @PostMapping(value = "/updateWhatsPlaying")
  @RequiresAccess
  public ResponseEntity<PluginResponse> updateWhatsPlaying(@RequestBody UpdateWhatsPlayingRequest request) {
    return this.pluginService.updateWhatsPlaying(request);
  }

  @PostMapping(value = "/updateNextScheduledSequence")
  @RequiresAccess
  public ResponseEntity<PluginResponse> updateNextScheduledSequence(@RequestBody UpdateNextScheduledRequest request) {
    return this.pluginService.updateNextScheduledSequence(request);
  }

  @GetMapping(value = "/viewerControlMode")
  @RequiresAccess
  public ResponseEntity<PluginResponse> viewerControlMode() {
    return this.pluginService.viewerControlMode();
  }

//  @GetMapping(value = "/highestVotedPlaylist")
//  @RequiresAccess
//  public ResponseEntity<HighestVotedPlaylistResponse> highestVotedPlaylist() {
//    return this.pluginService.highestVotedPlaylist();
//  }

  @PostMapping(value = "/pluginVersion")
  @RequiresAccess
  public ResponseEntity<PluginResponse> pluginVersion(@RequestBody PluginVersion request) {
    return this.pluginService.pluginVersion(request);
  }

  @GetMapping(value = "/remotePreferences")
  @RequiresAccess
  public ResponseEntity<RemotePreferenceResponse> remotePreferences() {
    return this.pluginService.remotePreferences();
  }

  @DeleteMapping(value = "/purgeQueue")
  @RequiresAccess
  public ResponseEntity<PluginResponse> purgeQueue() {
    return this.pluginService.purgeQueue();
  }

  @DeleteMapping(value = "/resetAllVotes")
  @RequiresAccess
  public ResponseEntity<PluginResponse> resetAllVotes() {
    return this.pluginService.resetAllVotes();
  }

  @PostMapping(value = "/toggleViewerControl")
  @RequiresAccess
  public ResponseEntity<PluginResponse> toggleViewerControl() {
    return this.pluginService.toggleViewerControl();
  }

  @PostMapping(value = "/updateViewerControl")
  @RequiresAccess
  public ResponseEntity<PluginResponse> updateViewerControl(@RequestBody ViewerControlRequest request) {
    return this.pluginService.updateViewerControl(request);
  }

//  @PostMapping(value = "/updateManagedPsa")
//  @RequiresAccess
//  public ResponseEntity<PluginResponse> updateManagedPsa(@RequestBody ManagedPSARequest request) {
//    return this.pluginService.updateManagedPsa(request);
//  }
}
