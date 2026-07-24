"""
Tests porting: cdd_sync_binary_utils_test.java — normalizePath, normalizePathForDatabase sections
"""
import unicodedata
import pytest
from core.path_utils import normalize_path, normalize_path_for_database, normalize_for_dedup


class TestNormalizePath:
    def test_nfc_output_from_nfd_input(self):
        nfd = unicodedata.normalize('NFD', 'Böhm')
        result = normalize_path(nfd)
        assert result == unicodedata.normalize('NFC', 'böhm')

    def test_lowercase(self):
        assert normalize_path('Crates/FOLDER/Track.MP3') == 'crates/folder/track.mp3'

    def test_strips_volumes_prefix(self):
        assert normalize_path('/Volumes/MyDrive/Crates/foo.mp3') == 'crates/foo.mp3'

    def test_already_relative(self):
        assert normalize_path('Crates/foo.mp3') == 'crates/foo.mp3'

    def test_empty_string(self):
        assert normalize_path('') == ''


class TestNormalizePathForDatabase:
    def test_strips_volumes_prefix(self):
        result = normalize_path_for_database('/Volumes/MyDrive/Crates/foo.mp3')
        assert result == 'Crates/foo.mp3'

    def test_preserves_case(self):
        result = normalize_path_for_database('/Volumes/MyDrive/Crates/TrackName.mp3')
        assert result == 'Crates/TrackName.mp3'

    def test_already_relative(self):
        result = normalize_path_for_database('Crates/foo.mp3')
        assert result == 'Crates/foo.mp3'


class TestNormalizeForDedup:
    def test_returns_filename_only(self):
        assert normalize_for_dedup('/Volumes/Drive/Folder/Track.mp3') == 'track.mp3'

    def test_nfc_lowercase(self):
        nfd = unicodedata.normalize('NFD', 'Niña.mp3')
        assert normalize_for_dedup(nfd) == unicodedata.normalize('NFC', 'niña.mp3')

    def test_no_slash(self):
        assert normalize_for_dedup('track.mp3') == 'track.mp3'
