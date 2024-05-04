package com.remotefalcon.plugins.api.model;

import com.remotefalcon.plugins.api.model.SyncPlaylistDetails;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SyncPlaylistRequest {
  private List<SyncPlaylistDetails> playlists;
}
