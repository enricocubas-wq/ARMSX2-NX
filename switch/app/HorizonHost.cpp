// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// Copyright(c) 2026: PalindromicBreadLoaf (palindromicbreadloaf@tuta.com)
// SPDX-License-Identifier: GPL-3.0+

// Host seam for the ARMSX2 Nintendo Switch (Horizon) application.

#include "common/Console.h"
#include "common/Error.h"
#include "common/Horizon/Horizon.h"
#include "common/Horizon/HorizonTuning.h"
#include "common/ProgressCallback.h"
#include "common/Threading.h"
#include "common/WindowInfo.h"

#include "pcsx2/Achievements.h"
#include "pcsx2/GameList.h"
#include "pcsx2/GS/GS.h"
#include "pcsx2/Host.h"
#include "pcsx2/ImGui/FullscreenUI.h"
#include "pcsx2/ImGui/ImGuiFullscreen.h"
#include "pcsx2/ImGui/ImGuiManager.h"
#include "pcsx2/Input/InputManager.h"
#include "pcsx2/MTGS.h"
#include "pcsx2/PerformanceMetrics.h"
#include "pcsx2/VMManager.h"

#include "HorizonHost.h"

#include <algorithm>
#include <atomic>
#include <array>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

namespace
{
	std::mutex s_cpu_queue_mutex;
	std::deque<std::function<void()>> s_cpu_queue;

	std::mutex s_cpu_done_mutex;
	std::condition_variable s_cpu_done_cv;

	std::thread::id s_cpu_thread_id;
	std::atomic_bool s_cpu_thread_valid{false};
	std::atomic_bool s_exit_requested{false};
	std::atomic_bool s_perf_log_enabled{false};

	// PerformanceMetrics refreshes every 0.5s; one log line summarises this many refreshes.
	constexpr u32 PERF_LOG_SAMPLES = 10;

	struct PerfLogAccumulator
	{
		u32 samples = 0;
		float speed_sum = 0.0f;
		float speed_min = 0.0f;
		float fps_sum = 0.0f;
		float ee_sum = 0.0f;
		float gs_sum = 0.0f;
		float vu_sum = 0.0f;
		float gpu_sum = 0.0f;
		float stall_vu_sum = 0.0f;
		float stall_gs_sum = 0.0f;
		float stall_vsync_sum = 0.0f;
		float gs_gpu_wait_sum = 0.0f;
		float gs_idle_sum = 0.0f;
		std::array<u32, 6> limiter_counts{};

		// Fault counters at the previous line, to report deltas.
		u64 last_lazy = 0;
		u64 last_limit = 0;
		u64 last_prot_reads = 0;
		u64 last_prot_read_bp = 0;
		u64 last_prot_writes = 0;
		u64 last_backpatches = 0;
		u64 last_unhandled = 0;
	};
	PerfLogAccumulator s_perf_log;

	void ResetPerfLogSamples()
	{
		s_perf_log.samples = 0;
		s_perf_log.speed_sum = s_perf_log.speed_min = s_perf_log.fps_sum = 0.0f;
		s_perf_log.ee_sum = s_perf_log.gs_sum = s_perf_log.vu_sum = s_perf_log.gpu_sum = 0.0f;
		s_perf_log.stall_vu_sum = s_perf_log.stall_gs_sum = s_perf_log.stall_vsync_sum = 0.0f;
		s_perf_log.gs_gpu_wait_sum = s_perf_log.gs_idle_sum = 0.0f;
		s_perf_log.limiter_counts.fill(0);
	}

	std::mutex s_gamelist_refresh_mutex;
	std::thread s_gamelist_refresh_thread;
	constexpr size_t RETROACHIEVEMENTS_INPUT_MAX_LENGTH = 256;

	bool GetRetroAchievementsKeyboardInput(const char* guide_text, bool password, std::string* output)
	{
		std::array<char, RETROACHIEVEMENTS_INPUT_MAX_LENGTH> buffer{};
		SwkbdConfig keyboard;
		if (R_FAILED(swkbdCreate(&keyboard, 0)))
		{
			ERROR_LOG("Failed to create the software keyboard");
			return false;
		}

		if (password)
			swkbdConfigMakePresetPassword(&keyboard);
		else
			swkbdConfigMakePresetDefault(&keyboard);
		swkbdConfigSetGuideText(&keyboard, guide_text);
		swkbdConfigSetOkButtonText(&keyboard, password ? "Login" : "Next");
		swkbdConfigSetStringLenMax(&keyboard, RETROACHIEVEMENTS_INPUT_MAX_LENGTH - 1);

		const Result result = swkbdShow(&keyboard, buffer.data(), buffer.size());
		swkbdClose(&keyboard);
		if (R_FAILED(result) || buffer.front() == '\0')
		{
			std::fill(buffer.begin(), buffer.end(), '\0');
			return false;
		}

		*output = buffer.data();
		std::fill(buffer.begin(), buffer.end(), '\0');
		return true;
	}

	void ShowRetroAchievementsLoginError(std::string message)
	{
		MTGS::RunOnGSThread([message = std::move(message)]() {
			if (ImGuiManager::InitializeFullscreenUI())
				ImGuiFullscreen::OpenInfoMessageDialog("RetroAchievements Login", std::move(message));
		});
	}

	void PromptForRetroAchievementsLogin(Achievements::LoginRequestReason reason)
	{
		Host::RunOnCPUThread([reason]() {
			std::string username;
			const char* username_prompt = (reason == Achievements::LoginRequestReason::TokenInvalid) ?
				"Saved login expired. Enter your RetroAchievements username." : "Enter your RetroAchievements username.";
			if (!GetRetroAchievementsKeyboardInput(username_prompt, false, &username))
				return;

			std::string password;
			if (!GetRetroAchievementsKeyboardInput("Enter your RetroAchievements password.", true, &password))
				return;

			Error error;
			const bool logged_in = Achievements::Login(username.c_str(), password.c_str(), &error);
			std::fill(password.begin(), password.end(), '\0');
			if (!logged_in)
			{
				ShowRetroAchievementsLoginError(error.IsValid() ? error.GetDescription() : "Login failed.");
			}
		}, false);
	}
} // namespace

void HorizonHost::SetCPUThread()
{
	s_cpu_thread_id = std::this_thread::get_id();
	s_cpu_thread_valid.store(true, std::memory_order_release);
}

bool HorizonHost::IsCPUThread()
{
	return s_cpu_thread_valid.load(std::memory_order_acquire) && std::this_thread::get_id() == s_cpu_thread_id;
}

void HorizonHost::RequestExit()
{
	s_exit_requested.store(true, std::memory_order_release);

	// Unblock VMManager::Execute() if a game is running
	if (VMManager::HasValidVM())
	{
		const VMState state = VMManager::GetState();
		if (state == VMState::Running || state == VMState::Paused)
			VMManager::SetState(VMState::Stopping);
	}
}

bool HorizonHost::IsExitRequested()
{
	return s_exit_requested.load(std::memory_order_acquire);
}

void HorizonHost::SetPerfLogEnabled(bool enabled)
{
	s_perf_log_enabled.store(enabled, std::memory_order_release);
}

std::optional<WindowInfo> Host::AcquireRenderWindow(bool recreate_window)
{
	(void)recreate_window;
	WindowInfo wi;
	wi.type = WindowInfo::Type::VI;
	wi.window_handle = nwindowGetDefault();
	wi.surface_width = 1280;
	wi.surface_height = 720;
	wi.surface_scale = 1.0f;
	wi.surface_refresh_rate = 60.0f;
	return wi;
}

void Host::ReleaseRenderWindow()
{
}

void Host::BeginPresentFrame()
{
}

std::optional<WindowInfo> Host::GetTopLevelWindowInfo()
{
	return std::nullopt;
}

void Host::RequestResizeHostDisplay(s32 width, s32 height)
{
}

bool Host::IsFullscreen()
{
	return false;
}

void Host::SetFullscreen(bool enabled)
{
}

// Settings no-ops
void Host::LoadSettings(SettingsInterface& si, std::unique_lock<std::mutex>& lock)
{
}

void Host::CheckForSettingsChanges(const Pcsx2Config& old_config)
{
}

bool Host::RequestResetSettings(bool folders, bool core, bool controllers, bool hotkeys, bool ui)
{
	return false;
}

void Host::SetDefaultUISettings(SettingsInterface& si)
{
}

// Diagnostics
void Host::ReportErrorAsync(const std::string_view title, const std::string_view message)
{
	if (!title.empty() && !message.empty())
		ERROR_LOG("ReportErrorAsync: {}: {}", title, message);
	else if (!message.empty())
		ERROR_LOG("ReportErrorAsync: {}", message);
}

void Host::ReportInfoAsync(const std::string_view title, const std::string_view message)
{
	if (!title.empty() && !message.empty())
		INFO_LOG("ReportInfoAsync: {}: {}", title, message);
	else if (!message.empty())
		INFO_LOG("ReportInfoAsync: {}", message);
}

bool Host::ConfirmMessage(const std::string_view title, const std::string_view message)
{
	WARNING_LOG("ConfirmMessage (auto yes): {}: {}", title, message);
	return true;
}

std::unique_ptr<ProgressCallback> Host::CreateHostProgressCallback()
{
	return nullptr;
}

void Host::OpenURL(const std::string_view url)
{
}

bool Host::CopyTextToClipboard(const std::string_view text)
{
	return false;
}

void Host::BeginTextInput()
{
}

void Host::EndTextInput()
{
}

bool Host::LocaleCircleConfirm()
{
	return false;
}

bool Host::InNoGUIMode()
{
	return true;
}

void Host::OnVMStarting()
{
	INFO_LOG("Host: VM starting");
}

void Host::OnVMStarted()
{
	INFO_LOG("Host: VM started");
}

void Host::OnVMDestroyed()
{
	INFO_LOG("Host: VM destroyed");
}

void Host::OnVMPaused()
{
}

void Host::OnVMResumed()
{
}

void Host::OnGameChanged(const std::string& title, const std::string& elf_override, const std::string& disc_path,
	const std::string& disc_serial, u32 disc_crc, u32 current_crc)
{
	INFO_LOG("Host: game changed - title='{}' serial='{}' crc={:08X}", title, disc_serial, disc_crc);
}

// Called from the GS thread each time PerformanceMetrics refreshes (every 0.5s).
// Writes one averaged line every few seconds so a play session can be diagnosed from the log
// file alone, without reading the OSD.
void Host::OnPerformanceMetricsUpdated()
{
	if (!s_perf_log_enabled.load(std::memory_order_acquire))
		return;

	if (VMManager::GetState() != VMState::Running)
	{
		ResetPerfLogSamples();
		return;
	}

	PerfLogAccumulator& a = s_perf_log;
	const float speed = PerformanceMetrics::GetSpeed();
	a.speed_min = (a.samples == 0) ? speed : std::min(a.speed_min, speed);
	a.speed_sum += speed;
	a.fps_sum += PerformanceMetrics::GetFPS();
	a.ee_sum += static_cast<float>(PerformanceMetrics::GetCPUThreadUsage());
	a.gs_sum += PerformanceMetrics::GetGSThreadUsage();
	a.vu_sum += PerformanceMetrics::GetVUThreadUsage();
	a.gpu_sum += PerformanceMetrics::GetGPUUsage();
	a.stall_vu_sum += PerformanceMetrics::GetEEStallVUTime();
	a.stall_gs_sum += PerformanceMetrics::GetEEStallGSTime();
	a.stall_vsync_sum += PerformanceMetrics::GetEEStallVsyncTime();
	a.gs_gpu_wait_sum += PerformanceMetrics::GetGSGpuWaitTime();
	a.gs_idle_sum += PerformanceMetrics::GetGSWorkWaitTime();
	const size_t limiter = static_cast<size_t>(PerformanceMetrics::GetLimiter());
	if (limiter < a.limiter_counts.size())
		a.limiter_counts[limiter]++;
	a.samples++;

	if (a.samples < PERF_LOG_SAMPLES)
		return;

	static constexpr std::array<const char*, 6> LIMITER_NAMES = {"?", "EE", "VU", "GS", "GPU", "limited"};
	size_t top_limiter = 0;
	for (size_t i = 1; i < a.limiter_counts.size(); i++)
	{
		if (a.limiter_counts[i] > a.limiter_counts[top_limiter])
			top_limiter = i;
	}

	const float n = static_cast<float>(a.samples);
	INFO_LOG("[PERF] speed {:.0f}% (min {:.0f}%) fps {:.1f} limiter {} ({}/{}) | load EE {:.0f}% GS {:.0f}% VU {:.0f}% "
			 "GPU {:.0f}% | EE waits ms/frame: vu {:.2f} gs {:.2f} vsync {:.2f} | GS waits ms/frame: gpu {:.2f} idle {:.2f}",
		a.speed_sum / n, a.speed_min, a.fps_sum / n, LIMITER_NAMES[top_limiter], a.limiter_counts[top_limiter], a.samples,
		a.ee_sum / n, a.gs_sum / n, a.vu_sum / n, a.gpu_sum / n, a.stall_vu_sum / n, a.stall_gs_sum / n,
		a.stall_vsync_sum / n, a.gs_gpu_wait_sum / n, a.gs_idle_sum / n);

	const Horizon::FaultStats& fs = Horizon::GetFaultStats();
	const u64 lazy = fs.lazy_resolved.load(std::memory_order_relaxed);
	const u64 limit = fs.limit_refusals.load(std::memory_order_relaxed);
	const u64 prot_reads = fs.protected_reads.load(std::memory_order_relaxed);
	const u64 prot_read_bp = fs.protected_read_backpatches.load(std::memory_order_relaxed);
	const u64 prot_writes = fs.protected_writes.load(std::memory_order_relaxed);
	const u64 backpatches = fs.backpatches.load(std::memory_order_relaxed);
	const u64 unhandled = fs.unhandled.load(std::memory_order_relaxed);
	INFO_LOG("[FAULT] fastmem pages {}/{} | since last line: mapped-in {} limit-refused {} code-page reads {} "
			 "(backpatched {}) code-page writes {} backpatches {} unhandled {} | totals: code-page reads {} writes {} "
			 "limit-refused {}",
		fs.live_pages.load(std::memory_order_relaxed), fs.live_page_limit.load(std::memory_order_relaxed),
		lazy - a.last_lazy, limit - a.last_limit, prot_reads - a.last_prot_reads, prot_read_bp - a.last_prot_read_bp,
		prot_writes - a.last_prot_writes, backpatches - a.last_backpatches, unhandled - a.last_unhandled, prot_reads,
		prot_writes, limit);
	a.last_lazy = lazy;
	a.last_limit = limit;
	a.last_prot_reads = prot_reads;
	a.last_prot_read_bp = prot_read_bp;
	a.last_prot_writes = prot_writes;
	a.last_backpatches = backpatches;
	a.last_unhandled = unhandled;

	ResetPerfLogSamples();
}

void Host::OnSaveStateLoading(const std::string_view filename)
{
}

void Host::OnSaveStateLoaded(const std::string_view filename, bool was_successful)
{
}

void Host::OnSaveStateSaved(const std::string_view filename)
{
}

void Host::OnCaptureStarted(const std::string& filename)
{
}

void Host::OnCaptureStopped()
{
}

// CPU things
void Host::PumpMessagesOnCPUThread()
{
	for (;;)
	{
		std::function<void()> task;
		{
			std::lock_guard<std::mutex> lock(s_cpu_queue_mutex);
			if (s_cpu_queue.empty())
				break;

			task = std::move(s_cpu_queue.front());
			s_cpu_queue.pop_front();
		}
		task();
	}
}

void Host::RunOnCPUThread(std::function<void()> function, bool block /* = false */)
{
	if (HorizonHost::IsCPUThread())
	{
		function();
		return;
	}

	if (!block)
	{
		std::lock_guard<std::mutex> lock(s_cpu_queue_mutex);
		s_cpu_queue.push_back(std::move(function));
		return;
	}

	// Qqueue a wrapper that signals completion, then wait for it.
	std::atomic_bool completed{false};
	{
		std::lock_guard<std::mutex> lock(s_cpu_queue_mutex);
		s_cpu_queue.push_back([&completed, &function]() {
			function();
			{
				std::lock_guard<std::mutex> done_lock(s_cpu_done_mutex);
				completed.store(true, std::memory_order_release);
			}
			s_cpu_done_cv.notify_all();
		});
	}

	std::unique_lock<std::mutex> done_lock(s_cpu_done_mutex);
	s_cpu_done_cv.wait(done_lock, [&completed]() { return completed.load(std::memory_order_acquire); });
}

void Host::RefreshGameListAsync(bool invalidate_cache)
{
	std::lock_guard<std::mutex> lock(s_gamelist_refresh_mutex);
	if (s_gamelist_refresh_thread.joinable())
		s_gamelist_refresh_thread.join();

	s_gamelist_refresh_thread = std::thread([invalidate_cache]() {
		Threading::SetNameOfCurrentThread("GameList Refresh");
		GameList::Refresh(invalidate_cache, false, nullptr);
	});
}

void Host::CancelGameListRefresh()
{
	std::lock_guard<std::mutex> lock(s_gamelist_refresh_mutex);
	if (s_gamelist_refresh_thread.joinable())
		s_gamelist_refresh_thread.join();
}

// Goodbye
void Host::RequestExitApplication(bool allow_confirm)
{
	HorizonHost::RequestExit();
}

void Host::RequestExitBigPicture()
{
	// Exit Big Picture just quits the application.
	HorizonHost::RequestExit();
}

void Host::RequestVMShutdown(bool allow_confirm, bool allow_save_state, bool default_save_state)
{
	// Stop the running VM and return to the FullscreenUI game selector
	if (VMManager::HasValidVM())
		VMManager::SetState(VMState::Stopping);
}

// Input no-ops
void Host::OnInputDeviceConnected(const std::string_view identifier, const std::string_view device_name)
{
}

void Host::OnInputDeviceDisconnected(const InputBindingKey key, const std::string_view identifier)
{
}

void Host::SetMouseMode(bool relative_mode, bool hide_cursor)
{
}

std::optional<u32> InputManager::ConvertHostKeyboardStringToCode(const std::string_view str)
{
	return std::nullopt;
}

std::optional<std::string> InputManager::ConvertHostKeyboardCodeToString(u32 code)
{
	return std::nullopt;
}

const char* InputManager::ConvertHostKeyboardCodeToIcon(u32 code)
{
	return nullptr;
}

void Host::OnAchievementsLoginSuccess(const char* username, u32 points, u32 sc_points, u32 unread_messages)
{
	INFO_LOG("RetroAchievements login succeeded for '{}' ({} hardcore, {} softcore points, {} unread messages)",
		username ? username : "", points, sc_points, unread_messages);
}

void Host::OnAchievementsLoginRequested(Achievements::LoginRequestReason reason)
{
	PromptForRetroAchievementsLogin(reason);
}

void Host::OnAchievementsHardcoreModeChanged(bool enabled)
{
	INFO_LOG("RetroAchievements hardcore mode {}", enabled ? "enabled" : "disabled");
}

void Host::OnAchievementsRefreshed()
{
}

void Host::OnCoverDownloaderOpenRequested()
{
}

void Host::OnCreateMemoryCardOpenRequested()
{
}

// File Selection stubs
bool Host::ShouldPreferHostFileSelector()
{
	return false;
}

void Host::OpenHostFileSelectorAsync(std::string_view title, bool select_directory, FileSelectorCallback callback,
	FileSelectorFilters filters, std::string_view initial_directory)
{
	callback(std::string());
}


s32 Host::Internal::GetTranslatedStringImpl(
	const std::string_view context, const std::string_view msg, char* tbuf, size_t tbuf_space)
{
	if (msg.size() > tbuf_space)
		return -1;
	else if (msg.empty())
		return 0;

	std::memcpy(tbuf, msg.data(), msg.size());
	return static_cast<s32>(msg.size());
}

std::string Host::TranslatePluralToString(const char* context, const char* msg, const char* disambiguation, int count)
{
	return std::string(msg);
}

BEGIN_HOTKEY_LIST(g_common_hotkeys)
END_HOTKEY_LIST()

BEGIN_HOTKEY_LIST(g_host_hotkeys)
END_HOTKEY_LIST()

void VMManager::Internal::ResetVMHotkeyState()
{
}
