// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#pragma once

// Sampling profiler for the Switch port. See HorizonProfiler.cpp.
namespace HorizonProfiler
{
	/// Starts the sampling thread. Must be called from the main (EE) thread, which becomes the
	/// "EE" target. The sampler idles until a virtual machine is running.
	void Start();

	/// Stops the sampling thread and waits for it to exit.
	void Stop();
} // namespace HorizonProfiler
