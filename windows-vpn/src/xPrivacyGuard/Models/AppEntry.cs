using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace xPrivacyGuard.Models;

public sealed class AppEntry : INotifyPropertyChanged
{
    private bool _includedInWarp;

    public required string Name { get; init; }
    public required string ExecutablePath { get; init; }
    public required string ProcessName { get; init; }
    public bool IsRunning { get; init; } = true;

    public string RunningLabel => IsRunning ? "Running" : "Saved";

    public bool IncludedInWarp
    {
        get => _includedInWarp;
        set
        {
            if (_includedInWarp == value) return;
            _includedInWarp = value;
            OnPropertyChanged();
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
}
