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

## Search

search-placeholder = Search Songs
search-empty-title = No songs found
search-empty-body = No songs found

## Song details and editor

song-title-label = Song Title
active-label = Active?
background-title = Background
solo-count-label = # Solos
composition-credits-label = Composition By
arrangement-credits-label = Arranged By
origin-label = Origin
arrangement-notes-label = Arrangement Info
lyrics-label = Lyrics
play-stats-title = Play Stats
total-plays-label = Total Play Count
gig-count-label = Gig Count
rehearsal-count-label = Rehearsal Count
score-label = Score
last-played-gig-label = Last Played Gig
last-played-rehearsal-label = Last Played Rehearsal
forum-title = Forum

## Sheet music

sheet-music-title = Sheet Music
choose-sheet-music-title = Choose Sheet Music File
# $section-name (String) - Name of the section receiving the sheet music.
choose-sheet-music-subtitle = For section: { $section-name }
other-sheet-music = Musescore, etc
# $title (String) - Display title of the sheet music file being removed.
remove-sheet-music-confirm = Remove { $title }?
