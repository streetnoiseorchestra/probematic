### Repertoire and song management

title = Repertoire
add-song = Add Song
sync-songs = Sync songs
# $count (Number) - Number of songs matching the current repertoire filters.
song-count =
    { $count ->
        [one] One song
       *[other] { $count } songs
    }
index-toolbar-label = Repertoire controls
detail-toolbar-label = Song controls
edit-toolbar-label = Song editor controls
filter-label = Repertoire
filter-current = Current
filter-old = Old
filter-all = All
last-played = Last Played
