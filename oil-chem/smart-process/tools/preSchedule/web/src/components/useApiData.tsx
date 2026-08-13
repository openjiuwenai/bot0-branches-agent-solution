"use client";
import { useEffect, useState } from "react";
import { ApiData } from "@/lib/types";

export function useApiData() {
  const [data, setData] = useState<ApiData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [noData, setNoData] = useState(false);

  useEffect(() => {
    fetch("/api/data", { cache: "no-store" })
      .then((res) => res.json())
      .then((d) => {
        if (d.error && !d.parameters) {
          setNoData(true);
        } else if (d.error) {
          setError(d.error);
        } else {
          setData(d);
        }
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  return { data, loading, error, noData };
}

export default useApiData;
