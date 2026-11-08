"use client";

import { Dialog as Primitive } from "radix-ui";
import { X } from "lucide-react";
import { useRef, type ReactNode } from "react";

export function Dialog({
  title,
  description,
  open,
  onOpenChange,
  children,
}: {
  title: string;
  description: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  children: ReactNode;
}) {
  const content = useRef<HTMLDivElement>(null);
  const opener = useRef<HTMLElement | null>(null);
  return (
    <Primitive.Root open={open} onOpenChange={onOpenChange}>
      <Primitive.Portal>
        <Primitive.Overlay className="dialog-overlay" />
        <Primitive.Content
          className="dialog-content"
          ref={content}
          onOpenAutoFocus={(event) => {
            opener.current = document.activeElement as HTMLElement;
            const field = content.current?.querySelector<HTMLElement>(
              "input:not([disabled]), select:not([disabled])",
            );
            if (field) {
              event.preventDefault();
              field.focus();
            }
          }}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            opener.current?.focus();
          }}
        >
          <div className="dialog-heading">
            <Primitive.Title>{title}</Primitive.Title>
            <Primitive.Close className="icon-button" aria-label="Đóng">
              <X size={18} />
            </Primitive.Close>
          </div>
          <Primitive.Description className="muted dialog-description">
            {description}
          </Primitive.Description>
          {children}
        </Primitive.Content>
      </Primitive.Portal>
    </Primitive.Root>
  );
}
