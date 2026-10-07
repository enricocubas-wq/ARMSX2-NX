// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#pragma once

#include "common/Pcsx2Defs.h"

#include <atomic>

// Switch-specific runtime tuning and diagnostics.
//
// Everything here is optional: each tweak has a toggle (read from the [Horizon] section of
// armsx2.ini by the host application) so a regression can be ruled out on the console without
// rebuilding.
namespace Horizon
{
	// --------------------------------------------------------------------------------------
	//  Thread placement
	// --------------------------------------------------------------------------------------
	// Background on why this exists:
	//  * hbloader gives homebrew cores 0-2 and makes core 0 the process default.
	//  * libnx creates every pthread/std::thread on the default core at priority 59, and the
	//    kernel pins a thread created that way to that single core.
	//  * The EE runs on the main thread (priority 44), which is pinned to core 0.
	// The result is that every helper thread (disc reader, audio, shader compiler, input...)
	// shares core 0 with the EE at a lower priority and only runs while the EE is blocked.
	//
	// With thread tuning enabled, helper threads may run on any allowed core (preferring core 1),
	// and the GS/VU threads are raised to the EE thread's priority so helpers never time-slice
	// against them.

	/// Captures the calling (main/EE) thread's priority and stores the toggle.
	/// Must be called once from the main thread before any other thread is created.
	void InitThreadTuning(bool enabled);

	bool IsThreadTuningEnabled();

	/// Applies the placement policy for the calling thread, selected by its name.
	/// Called from Threading::SetNameOfCurrentThread().
	void ApplyThreadPolicy(const char* name);

	// --------------------------------------------------------------------------------------
	//  EE code-page read faults
	// --------------------------------------------------------------------------------------
	// Horizon cannot make a fastmem alias read-only, so a write-protected code page is unmapped
	// from the fastmem arena and *reads* of it fault as well. When enabled, such a read is routed
	// through the slow path (backpatch) instead of being treated as a write to code.
	void SetCodePageReadBackpatchEnabled(bool enabled);
	bool IsCodePageReadBackpatchEnabled();

	// --------------------------------------------------------------------------------------
	//  Diagnostics
	// --------------------------------------------------------------------------------------
	struct FaultStats
	{
		std::atomic<u64> lazy_resolved{0}; ///< fastmem faults resolved by mapping pages in
		std::atomic<u64> lazy_pages{0}; ///< pages mapped by those faults
		std::atomic<u64> limit_refusals{0}; ///< faults refused because the live page limit was hit
		std::atomic<u64> protected_reads{0}; ///< reads that faulted on a write-protected code page
		std::atomic<u64> protected_read_backpatches{0}; ///< ...of which were backpatched
		std::atomic<u64> protected_writes{0}; ///< writes that faulted on a write-protected code page
		std::atomic<u64> backpatches{0}; ///< ordinary (non code page) backpatches
		std::atomic<u64> unhandled{0}; ///< faults nothing could handle
		std::atomic<u32> live_pages{0}; ///< fastmem alias pages currently mapped
		std::atomic<u32> live_page_limit{0};
	};

	FaultStats& GetFaultStats();
} // namespace Horizon
